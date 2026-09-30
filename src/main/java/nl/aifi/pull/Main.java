package nl.aifi.pull;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * <pre>
 *   aifi-pull-agent [run]            run the agent (what the Windows service does)
 *   aifi-pull-agent check            validate the configuration and test the proxy and every destination
 *   aifi-pull-agent status           list the pull jobs in the spool
 *   aifi-pull-agent retry &lt;job|all&gt;  re-queue a waiting / FAILED job (picked up within a minute)
 *   aifi-pull-agent version
 *   options: --config-dir &lt;dir&gt;   default: config
 * </pre>
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Main() {}

    public static void main(String[] args) {
        String command = "run";
        String arg = null;
        Path configDir = Path.of("config");
        for (int i = 0; i < args.length; i++) {
            if ("--config-dir".equals(args[i]) && i + 1 < args.length) configDir = Path.of(args[++i]);
            else if (command.equals("run") && i == 0) command = args[i];
            else arg = args[i];
        }
        LogSetup.console();
        int rc;
        try {
            switch (command) {
                case "run": rc = run(configDir); break;
                case "check": rc = check(configDir); break;
                case "status": rc = status(configDir); break;
                case "retry": rc = retry(configDir, arg); break;
                case "version": System.out.println("AIFI Pull Agent " + version()); rc = 0; break;
                default:
                    System.err.println("Unknown command '" + command + "'. Commands: run, check, status, retry <job|all>, version");
                    rc = 64;
            }
        } catch (Exception e) {
            LOG.severe(e.getMessage() == null ? e.toString() : e.getMessage());
            rc = 1;
        }
        if (!"run".equals(command) || rc != 0) System.exit(rc);
    }

    private static ConfigLoader.Loaded load(Path configDir) throws Exception {
        ConfigLoader.Loaded l;
        try {
            l = ConfigLoader.load(configDir);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Configuration error in " + configDir.resolve(ConfigLoader.FILE) + ": "
                    + e.getMessage(), e);
        }
        if (l.created) LOG.warning("Created " + l.file.toAbsolutePath() + " - fill it in (see docs/HANDLEIDING.md), then start again");
        return l;
    }

    private static boolean valid(ConfigLoader.Loaded l) {
        List<String> errors = l.config.errors();
        for (String e : errors) LOG.severe("Configuration: " + e);
        for (String w : l.config.warnings()) LOG.warning("Security: " + w);
        return errors.isEmpty();
    }

    private static int run(Path configDir) throws Exception {
        ConfigLoader.Loaded l = load(configDir);
        LogSetup.configure(l.config.logging);
        LOG.info("AIFI Pull Agent " + version() + " starting (Java " + System.getProperty("java.version") + ")");
        if (!valid(l)) {
            LOG.severe("Not starting: fix the configuration errors above in " + l.file.toAbsolutePath());
            return 2;
        }
        Agent agent = new Agent(l.config);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Stopping");
            agent.close();
            LOG.info("Stopped");
            stopped.countDown();
        }, "aifipull-shutdown"));
        try {
            agent.start();
        } catch (Exception e) {
            LOG.severe("Start failed: " + e.getMessage());
            agent.close();
            return 1;
        }
        LOG.info("Started");
        stopped.await();
        return 0;
    }

    private static int check(Path configDir) throws Exception {
        ConfigLoader.Loaded l = load(configDir);
        Config c = l.config;
        if (!valid(l)) return 2;
        int failures = 0;
        System.out.println("DICOM Web Proxy (source) " + c.source.baseUrl);
        try {
            JobStore probe = new JobStore(Files.createTempDirectory("aifipull-check"));
            System.out.println("  OK   " + new DicomWebClient(c.source, probe).check());
            JobStore.deleteTree(probe.root());
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL " + e.getMessage());
        }
        for (Config.Destination d : c.destinations) {
            System.out.println("Destination " + d.name + " (route " + d.route + ") " + d.aeTitle + "@" + d.host + ":" + d.port
                    + (d.tls.enabled ? " (TLS)" : ""));
            try {
                System.out.println("  OK   C-ECHO status 0x" + Integer.toHexString(StoreScu.echo(d)));
            } catch (Exception e) {
                failures++;
                System.out.println("  FAIL " + e.getMessage());
            }
        }
        System.out.println(failures == 0 ? "All connections OK" : failures + " connection(s) failed");
        return failures == 0 ? 0 : 1;
    }

    private static int status(Path configDir) throws Exception {
        ConfigLoader.Loaded l = load(configDir);
        Path dir = Path.of(l.config.spool.dir);
        if (!Files.isDirectory(dir.resolve("jobs"))) {
            System.out.println("No pull jobs (" + dir + ")");
            return 0;
        }
        JobStore store = new JobStore(dir);
        List<String> jobs = store.jobIds();
        if (jobs.isEmpty()) {
            System.out.println("No pull jobs - every request has been delivered");
            return 0;
        }
        System.out.printf("%-44s %-12s %-10s %9s %9s %8s %-16s %s%n", "JOB (KOS UID)", "ROUTE", "STATUS", "INSTANCES",
                "DELIVERED", "ATTEMPTS", "NEXT ATTEMPT", "LAST ERROR");
        for (String job : jobs) {
            Properties st = store.readState(job);
            long next = JobStore.longProp(st, "nextAttemptAt");
            System.out.printf("%-44s %-12s %-10s %9s %9d %8s %-16s %s%n", job, st.getProperty("route", ""),
                    JobStore.status(st), st.getProperty("instances", "?"), store.delivered(job).size(),
                    st.getProperty("attempts", "0"),
                    JobStore.status(st) == JobStore.Status.RETRY_WAIT && next > 0 ? TS.format(Instant.ofEpochMilli(next)) : "-",
                    st.getProperty("lastError", ""));
        }
        return 0;
    }

    private static int retry(Path configDir, String ref) throws Exception {
        if (ref == null) {
            System.err.println("Usage: retry <job|all>   (job as shown by 'status')");
            return 64;
        }
        ConfigLoader.Loaded l = load(configDir);
        JobStore store = new JobStore(Path.of(l.config.spool.dir));
        int n = 0;
        for (String job : store.jobIds()) {
            if ("all".equalsIgnoreCase(ref) || ref.equals(job)) {
                store.requestRetry(job);
                n++;
            }
        }
        System.out.println(n == 0 ? "No job matches '" + ref + "'"
                : n + " job(s) queued for a new attempt; the running agent picks them up within a minute");
        return n == 0 ? 1 : 0;
    }

    static String version() {
        try (InputStream in = Main.class.getResourceAsStream("version.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("version", "dev");
        } catch (Exception e) {
            return "dev";
        }
    }
}
