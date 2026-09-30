package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Works one pull job:
 * <pre>
 *   1. store what an earlier attempt already retrieved
 *   2. retrieve the study with WADO-RS (GET /studies/{uid}) until the source had all of it
 *   3. C-STORE to the route's destination; record every accepted instance before deleting it
 *   4. done when the study was retrieved completely and every instance has been accepted
 * </pre>
 * The gateway offers a study only when it is complete, so an answer with instances is the
 * whole study. When the KOS says how many instances there are and fewer came back, the
 * study is retrieved again later (instances already delivered are skipped).
 *
 * <p>The route comes from the KOS; when JiveX removed it there, from the route name the gateway
 * writes into every instance ({@link #ROUTE_CREATOR} / {@link #ROUTE_TAG}).
 */
public final class Puller {

    private static final Logger LOG = Logger.getLogger(Puller.class.getName());
    private static final int STORE_CHUNK = 200;
    static final String ROUTE_CREATOR = "AIFIGW";
    static final int ROUTE_TAG = 0x00091011;

    public enum Outcome { DONE, RETRY_WAIT, FAILED }

    private final Config cfg;
    private final JobStore store;
    private final DicomWebClient source;

    public Puller(Config cfg, JobStore store, DicomWebClient source) {
        this.cfg = cfg;
        this.store = store;
        this.source = source;
    }

    public Outcome process(String job) {
        Properties st;
        KosManifest m;
        try {
            st = store.readState(job);
            st.setProperty("status", JobStore.Status.WORKING.name());
            st.setProperty("attempts", Long.toString(JobStore.longProp(st, "attempts") + 1));
            store.writeState(job, st);
            Attributes kos;
            try (DicomInputStream in = new DicomInputStream(store.kosFile(job).toFile())) {
                in.readFileMetaInformation();
                kos = in.readDataset();
            }
            m = KosManifest.parse(kos);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "job " + job + ": cannot read it: " + e.getMessage(), e);
            return fail(job, "cannot read the job: " + e.getMessage(), true);
        }
        String route = !m.route.isEmpty() ? m.route : st.getProperty("route", "");
        String tag = "[" + (route.isEmpty() ? "?" : route) + "] study " + m.studyUid;
        LOG.info(tag + ": pulling" + (m.expected > 0 ? " " + m.expected + " instance(s)" : "")
                + " (attempt " + st.getProperty("attempts") + ")");
        List<String> problems = new ArrayList<>();
        try {
            // 1. what an earlier attempt retrieved
            route = storePending(job, st, route, problems);

            // 2. retrieve
            boolean retrieved = Boolean.parseBoolean(st.getProperty("retrieved", "false"));
            String notAvailable = null;
            if (!retrieved) {
                Set<String> delivered = store.delivered(job);
                int http = source.retrieve("/studies/" + m.studyUid, f -> accept(job, f, m.studyUid, delivered));
                int have = union(delivered, pendingSops(job)).size();
                if (http == 200 && have > 0 && have >= m.expected) {
                    retrieved = true;
                    st.setProperty("retrieved", "true");
                    st.setProperty("instances", Integer.toString(have));
                    store.writeState(job, st);
                } else if (http == 200 || http == 204 || http == 404) {
                    notAvailable = have == 0 ? "the study is not (yet) available at the source"
                            : "only " + have + " of " + m.expected + " instance(s) available at the source so far";
                } else {
                    problems.add("HTTP " + http + " " + DicomWebClient.hint(http));
                }
            }

            // 3. store
            route = storePending(job, st, route, problems);

            // 4. done?
            int delivered = store.delivered(job).size();
            if (retrieved && pendingSops(job).isEmpty()) {
                Config.Destination dest = cfg.destinationFor(route);
                store.complete(job);
                LOG.info("[" + route + "] study " + m.studyUid + ": DELIVERED " + delivered + " instance(s)"
                        + (dest == null ? "" : " to " + dest.name + " (" + dest.aeTitle + "@" + dest.host + ":" + dest.port + ")")
                        + "; job removed");
                return Outcome.DONE;
            }
            List<String> why = new ArrayList<>();
            if (notAvailable != null) why.add(notAvailable);
            why.addAll(problems.subList(0, Math.min(3, problems.size())));
            return fail(job, delivered + " instance(s) delivered so far; " + String.join("; ", why), true);
        } catch (DicomWebClient.SourceException e) {
            return fail(job, e.getMessage(), true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, tag + ": unexpected failure", e);
            return fail(job, e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    /** Keep a retrieved part when it is an instance of this study that is not there yet. */
    private void accept(String job, Path file, String studyUid, Set<String> delivered) throws IOException {
        String sop;
        String study;
        try (DicomInputStream in = new DicomInputStream(file.toFile())) {
            Attributes fmi = in.readFileMetaInformation();
            if (fmi == null) throw new IOException("part without DICOM file meta information");
            Attributes head = in.readDataset(-1, Tag.StudyID);
            sop = fmi.getString(Tag.MediaStorageSOPInstanceUID, head.getString(Tag.SOPInstanceUID));
            study = head.getString(Tag.StudyInstanceUID);
        }
        if (!studyUid.equals(study) || !KosManifest.isUid(sop) || delivered.contains(sop)) return;
        Path target = store.instancesDir(job).resolve(sop + ".dcm");
        if (Files.exists(target)) return;
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private Set<String> pendingSops(String job) throws IOException {
        Set<String> out = new HashSet<>();
        for (Path p : pendingFiles(job)) {
            String n = p.getFileName().toString();
            out.add(n.substring(0, n.length() - 4));
        }
        return out;
    }

    private List<Path> pendingFiles(String job) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(store.instancesDir(job), "*.dcm")) {
            for (Path p : ds) files.add(p);
        }
        files.sort(null);
        return files;
    }

    /** The route the gateway wrote into a retrieved instance, or "". */
    static String routeOf(Path instance) {
        try (DicomInputStream in = new DicomInputStream(instance.toFile())) {
            Attributes head = in.readDataset(-1, Tag.StudyInstanceUID);
            String r = head.getString(ROUTE_CREATOR, ROUTE_TAG, (String) null);
            return r != null && r.trim().matches("[a-z0-9][a-z0-9-]{0,31}") ? r.trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * C-STORE everything retrieved but not yet stored; record accepted instances, then delete them.
     *
     * @return the route, learned from the instances when the KOS did not name one
     */
    private String storePending(String job, Properties st, String route, List<String> problems) throws Exception {
        List<Path> files = pendingFiles(job);
        if (files.isEmpty()) return route;
        if (route.isEmpty()) {
            route = routeOf(files.get(0));
            if (!route.isEmpty()) {
                st.setProperty("route", route);
                store.writeState(job, st);
            }
        }
        Config.Destination dest = cfg.destinationFor(route);
        if (dest == null) {
            throw new DicomWebClient.SourceException("no destination configured for route '" + route
                    + "' (add one, or one with route: \"*\")", null);
        }
        for (int i = 0; i < files.size(); i += STORE_CHUNK) {
            List<Path> chunk = files.subList(i, Math.min(files.size(), i + STORE_CHUNK));
            Map<String, Integer> status;
            try {
                status = StoreScu.store(dest, chunk);
            } catch (Exception e) {
                throw new DicomWebClient.SourceException("C-STORE to " + dest.name + " (" + dest.aeTitle + "@" + dest.host
                        + ":" + dest.port + ") failed: " + e.getMessage(), e);
            }
            List<String> ok = new ArrayList<>();
            Map<Integer, Integer> refused = new LinkedHashMap<>();
            for (Path f : chunk) {
                String n = f.getFileName().toString();
                String sop = n.substring(0, n.length() - 4);
                Integer s = status.get(sop);
                if (StoreScu.isStored(s)) ok.add(sop);
                else refused.merge(s == null ? -1 : s, 1, Integer::sum);
            }
            store.appendDelivered(job, ok);
            for (String sop : ok) Files.deleteIfExists(store.instancesDir(job).resolve(sop + ".dcm"));
            for (Map.Entry<Integer, Integer> r : refused.entrySet()) {
                problems.add(r.getValue() + " refused by " + dest.name + " with status "
                        + (r.getKey() < 0 ? "none (association ended)" : "0x" + Integer.toHexString(r.getKey())));
            }
        }
        return route;
    }

    private Outcome fail(String job, String message, boolean retryable) {
        try {
            Properties st = store.readState(job);
            st.setProperty("lastError", message.length() > 2000 ? message.substring(0, 2000) : message);
            long attempts = JobStore.longProp(st, "attempts");
            int max = cfg.retry.maxAttempts;
            boolean exhausted = max > 0 && attempts >= max;
            if (retryable && !exhausted) {
                long delay = Math.min((long) cfg.retry.maxDelaySeconds,
                        (long) cfg.retry.initialDelaySeconds * (1L << Math.min(20, Math.max(0, attempts - 1))));
                st.setProperty("nextAttemptAt", Long.toString(System.currentTimeMillis() + delay * 1000));
                st.setProperty("status", JobStore.Status.RETRY_WAIT.name());
                store.writeState(job, st);
                LOG.warning("job " + job + " (study " + st.getProperty("studyUid") + "): attempt " + attempts
                        + (max > 0 ? "/" + max : "") + " incomplete, retry in " + delay + " s: " + message);
                return Outcome.RETRY_WAIT;
            }
            st.setProperty("status", JobStore.Status.FAILED.name());
            store.writeState(job, st);
            LOG.severe("job " + job + " (study " + st.getProperty("studyUid") + "): FAILED after " + attempts
                    + " attempts, kept; fix the cause, then run 'aifi-pull-agent retry': " + message);
            return Outcome.FAILED;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "job " + job + ": cannot record the failure", e);
            return Outcome.RETRY_WAIT;
        }
    }
}
