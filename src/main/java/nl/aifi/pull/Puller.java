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
import java.util.LinkedHashSet;
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
 *   2. retrieve the missing instances with WADO-RS (per series; one by one when only a few are missing)
 *   3. C-STORE them to the route's destination; record every accepted instance before deleting it
 *   4. done when every instance in the KOS has been accepted, otherwise retry later
 * </pre>
 * Instances the source returns that the KOS does not list are not forwarded.
 */
public final class Puller {

    private static final Logger LOG = Logger.getLogger(Puller.class.getName());
    private static final int STORE_CHUNK = 200;

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
        String tag = "[" + (m.route.isEmpty() ? "-" : m.route) + "] study " + m.studyUid;
        Config.Destination dest = cfg.destinationFor(m.route);
        if (dest == null) {
            return fail(job, "no destination configured for route '" + m.route + "' (add one, or one with route: \"*\")", true);
        }
        int total = m.instanceCount();
        LOG.info(tag + ": pulling " + total + " instance(s) for " + dest.name + " (attempt " + st.getProperty("attempts") + ")");
        List<String> problems = new ArrayList<>();
        try {
            storePending(job, dest, problems);

            Set<String> have = new HashSet<>(store.delivered(job));
            have.addAll(pendingSops(job));
            Set<String> referenced = new HashSet<>();
            m.series.values().forEach(referenced::addAll);
            int notAvailable = 0;
            for (Map.Entry<String, Set<String>> se : m.series.entrySet()) {
                Set<String> missing = new LinkedHashSet<>(se.getValue());
                missing.removeAll(have);
                if (missing.isEmpty()) continue;
                boolean partlyThere = missing.size() < se.getValue().size();
                String seriesPath = "/studies/" + m.studyUid + "/series/" + se.getKey();
                if (partlyThere && missing.size() <= cfg.source.instanceFetchThreshold) {
                    for (String sop : missing) {
                        int http = source.retrieve(seriesPath + "/instances/" + sop, f -> accept(job, f, m.studyUid, referenced));
                        if (http != 200 && http != 404 && http != 204) problems.add("HTTP " + http + " " + DicomWebClient.hint(http));
                    }
                } else {
                    int http = source.retrieve(seriesPath, f -> accept(job, f, m.studyUid, referenced));
                    if (http != 200 && http != 404 && http != 204) {
                        problems.add("series " + se.getKey() + ": HTTP " + http + " " + DicomWebClient.hint(http));
                    }
                }
                Set<String> nowHave = pendingSops(job);
                nowHave.addAll(store.delivered(job));
                for (String sop : missing) if (!nowHave.contains(sop)) notAvailable++;
                storePending(job, dest, problems);                  // keep the spool small on large studies
            }
            storePending(job, dest, problems);

            Set<String> delivered = store.delivered(job);
            int done = 0;
            for (String sop : referenced) if (delivered.contains(sop)) done++;
            if (done == total) {
                store.complete(job);
                LOG.info(tag + ": DELIVERED " + total + " instance(s) to " + dest.name + " (" + dest.aeTitle + "@"
                        + dest.host + ":" + dest.port + "); job removed");
                return Outcome.DONE;
            }
            int missingNow = total - done;
            String msg = done + " of " + total + " instance(s) delivered; " + missingNow + " missing"
                    + (notAvailable > 0 ? " (" + notAvailable + " not (yet) available at the source)" : "")
                    + (problems.isEmpty() ? "" : ": " + String.join("; ", problems.subList(0, Math.min(3, problems.size()))));
            return fail(job, msg, true);
        } catch (DicomWebClient.SourceException e) {
            return fail(job, e.getMessage(), true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, tag + ": unexpected failure", e);
            return fail(job, e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    /** Keep a retrieved part when it is a referenced instance of this study that is not there yet. */
    private void accept(String job, Path file, String studyUid, Set<String> referenced) throws IOException {
        String sop;
        String study;
        try (DicomInputStream in = new DicomInputStream(file.toFile())) {
            Attributes fmi = in.readFileMetaInformation();
            Attributes head = in.readDataset(-1, Tag.StudyID);
            sop = fmi != null ? fmi.getString(Tag.MediaStorageSOPInstanceUID) : head.getString(Tag.SOPInstanceUID);
            if (sop == null) sop = head.getString(Tag.SOPInstanceUID);
            study = head.getString(Tag.StudyInstanceUID);
            if (fmi == null) throw new IOException("part without DICOM file meta information");
        }
        if (sop == null || !referenced.contains(sop) || !studyUid.equals(study)) return;   // not asked for
        if (!KosManifest.isUid(sop)) return;
        Path target = store.instancesDir(job).resolve(sop + ".dcm");
        if (Files.exists(target)) return;
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private Set<String> pendingSops(String job) throws IOException {
        Set<String> out = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(store.instancesDir(job), "*.dcm")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - 4));
            }
        }
        return out;
    }

    /** C-STORE everything retrieved but not yet stored; record accepted instances, then delete them. */
    private void storePending(String job, Config.Destination dest, List<String> problems) throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(store.instancesDir(job), "*.dcm")) {
            for (Path p : ds) files.add(p);
        }
        files.sort(null);
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
