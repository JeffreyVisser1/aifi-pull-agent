package nl.aifi.pull;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs jobs: a new job right away, a waiting job at its next attempt, everything left in the
 * spool after a restart, and jobs an administrator re-queued with {@code retry}.
 */
public final class Scheduler implements KosScp.Listener {

    private static final Logger LOG = Logger.getLogger(Scheduler.class.getName());
    static final long SWEEP_SECONDS = Math.max(1, Long.getLong("aifipull.sweepSeconds", 20));
    private static final long REPORT_MS = 3_600_000;

    private final Config cfg;
    private final JobStore store;
    private final Puller puller;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aifipull-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers;
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private long lastReport;

    public Scheduler(Config cfg, JobStore store, Puller puller) {
        this.cfg = cfg;
        this.store = store;
        this.puller = puller;
        this.workers = Executors.newFixedThreadPool(cfg.workers, r -> {
            Thread t = new Thread(r, "aifipull-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        timer.scheduleWithFixedDelay(this::sweepSafely, 0, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        timer.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.warning("Stopping with a job still running; it is resumed at the next start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
    }

    @Override
    public void onJob(KosManifest m) {
        schedule(m.kosUid, cfg.retry.firstAttemptDelaySeconds * 1000L);
    }

    private void schedule(String job, long delayMs) {
        if (timer.isShutdown()) return;
        ScheduledFuture<?> old = pending.put(job, timer.schedule(() -> submit(job), Math.max(0, delayMs), TimeUnit.MILLISECONDS));
        if (old != null) old.cancel(false);
    }

    private void submit(String job) {
        pending.remove(job);
        if (!running.add(job)) return;
        workers.submit(() -> {
            Puller.Outcome outcome = Puller.Outcome.RETRY_WAIT;
            try {
                outcome = puller.process(job);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "job " + job + ": crashed", e);
            } finally {
                running.remove(job);
            }
            if (outcome == Puller.Outcome.RETRY_WAIT) {
                try {
                    long at = JobStore.longProp(store.readState(job), "nextAttemptAt");
                    schedule(job, Math.max(1000, at - System.currentTimeMillis()));
                } catch (IOException | IllegalArgumentException e) {
                    schedule(job, cfg.retry.initialDelaySeconds * 1000L);
                }
            }
        });
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "Spool sweep failed: " + e.getMessage(), e);
        }
    }

    void sweep() throws IOException {
        long now = System.currentTimeMillis();
        int queued = 0;
        int failed = 0;
        for (String job : store.jobIds()) {
            if (running.contains(job)) { queued++; continue; }
            Properties st = store.readState(job);
            if (store.takeRetryRequest(job)) {
                st.setProperty("attempts", "0");
                st.setProperty("nextAttemptAt", "0");
                st.setProperty("status", JobStore.Status.RETRY_WAIT.name());
                store.writeState(job, st);
                LOG.info("job " + job + ": retry requested by an administrator");
                schedule(job, 0);
                queued++;
                continue;
            }
            if (pending.containsKey(job)) { queued++; continue; }
            switch (JobStore.status(st)) {
                case RECEIVED:
                case WORKING:                          // interrupted by a restart
                    schedule(job, 0);
                    queued++;
                    break;
                case RETRY_WAIT:
                    schedule(job, Math.max(0, JobStore.longProp(st, "nextAttemptAt") - now));
                    queued++;
                    break;
                case FAILED:
                    int days = cfg.spool.failedRetentionDays;
                    long updated = JobStore.longProp(st, "updatedAt");
                    if (days > 0 && updated > 0 && now - updated > days * 86_400_000L) {
                        store.deleteJob(job);
                        LOG.warning("job " + job + ": deleted after " + days + " days in FAILED state (spool.failedRetentionDays)");
                    } else {
                        failed++;
                    }
                    break;
                default:
                    break;
            }
        }
        if (now - lastReport >= REPORT_MS) {
            lastReport = now;
            store.purgeDone(30);
            String msg = "Spool: " + queued + " job(s) queued, " + failed + " FAILED";
            if (failed > 0) LOG.severe(msg + " - see 'aifi-pull-agent status'");
            else if (queued > 0) LOG.info(msg);
        }
    }
}
