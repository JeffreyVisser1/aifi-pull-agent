package nl.aifi.pull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The on-disk job queue. A job exists from the moment its KOS was acknowledged until every
 * referenced instance has been accepted by the destination:
 *
 * <pre>
 *   spool/jobs/&lt;KOS SOPInstanceUID&gt;/kos.dcm             the pull request as received
 *   spool/jobs/&lt;KOS SOPInstanceUID&gt;/state.properties    status, attempts, last error
 *   spool/jobs/&lt;KOS SOPInstanceUID&gt;/delivered.list      SOP Instance UIDs the destination accepted
 *   spool/jobs/&lt;KOS SOPInstanceUID&gt;/instances/*.dcm     retrieved, not yet stored
 *   spool/jobs/&lt;KOS SOPInstanceUID&gt;/retry.request       written by "aifi-pull-agent retry"
 *   spool/incoming/                                      downloads in progress
 *   spool/done/&lt;KOS SOPInstanceUID&gt;                      completed requests (a re-sent KOS is ignored)
 * </pre>
 */
public final class JobStore implements AutoCloseable {

    public enum Status { RECEIVED, WORKING, RETRY_WAIT, FAILED }

    private final Path root;
    private FileChannel lockChannel;
    private FileLock processLock;

    public JobStore(Path root) throws IOException {
        this.root = root.toAbsolutePath();
        Files.createDirectories(jobsDir());
        Files.createDirectories(incomingDir());
        Files.createDirectories(doneDir());
    }

    public Path doneDir() { return root.resolve("done"); }

    public Path root() { return root; }
    public Path jobsDir() { return root.resolve("jobs"); }
    public Path incomingDir() { return root.resolve("incoming"); }

    public Path jobDir(String kosUid) {
        if (!KosManifest.isUid(kosUid)) throw new IllegalArgumentException("Invalid job id");
        return jobsDir().resolve(kosUid);
    }

    public Path instancesDir(String kosUid) { return jobDir(kosUid).resolve("instances"); }

    /** Only one agent process may work on a spool. */
    public void lockExclusive() throws IOException {
        lockChannel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            processLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            processLock = null;
        }
        if (processLock == null) {
            lockChannel.close();
            throw new IOException("The spool " + root + " is in use by another running pull agent");
        }
    }

    @Override
    public void close() throws IOException {
        if (processLock != null) processLock.release();
        if (lockChannel != null) lockChannel.close();
    }

    /**
     * Store a new job durably (KOS + initial state) before the KOS is acknowledged.
     *
     * @return false when a job for this KOS already exists (a re-sent notification)
     */
    public synchronized boolean create(KosManifest m, byte[] kosPart10) throws IOException {
        Path dir = jobDir(m.kosUid);
        if (Files.exists(dir) || Files.exists(doneDir().resolve(m.kosUid))) return false;
        Path tmp = jobsDir().resolve("." + UUID.randomUUID());
        Files.createDirectories(tmp.resolve("instances"));
        writeDurably(tmp.resolve("kos.dcm"), kosPart10);
        Properties p = new Properties();
        p.setProperty("status", Status.RECEIVED.name());
        p.setProperty("studyUid", m.studyUid);
        p.setProperty("route", m.route);
        p.setProperty("instances", Integer.toString(m.instanceCount()));
        p.setProperty("receivedAt", Long.toString(System.currentTimeMillis()));
        p.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        writeProps(tmp.resolve("state.properties"), p);
        Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    public List<String> jobIds() throws IOException {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(jobsDir())) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) && KosManifest.isUid(n)) out.add(n);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    public Path kosFile(String kosUid) { return jobDir(kosUid).resolve("kos.dcm"); }

    public Properties readState(String kosUid) throws IOException {
        Properties p = new Properties();
        Path f = jobDir(kosUid).resolve("state.properties");
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) { p.load(in); }
        }
        return p;
    }

    public void writeState(String kosUid, Properties p) throws IOException {
        p.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        writeProps(jobDir(kosUid).resolve("state.properties"), p);
    }

    public static Status status(Properties p) {
        try { return Status.valueOf(p.getProperty("status", "RECEIVED")); } catch (IllegalArgumentException e) { return Status.RECEIVED; }
    }

    public static long longProp(Properties p, String k) {
        try { return Long.parseLong(p.getProperty(k, "0")); } catch (NumberFormatException e) { return 0; }
    }

    public Set<String> delivered(String kosUid) throws IOException {
        Path f = jobDir(kosUid).resolve("delivered.list");
        Set<String> out = new LinkedHashSet<>();
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f, StandardCharsets.US_ASCII)) if (!l.isBlank()) out.add(l.trim());
        }
        return out;
    }

    /** Record instances the destination accepted (flushed before the local copies are deleted). */
    public void appendDelivered(String kosUid, List<String> sops) throws IOException {
        if (sops.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : sops) sb.append(s).append('\n');
        try (FileChannel ch = FileChannel.open(jobDir(kosUid).resolve("delivered.list"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.US_ASCII)));
            ch.force(true);
        }
    }

    public void deleteJob(String kosUid) throws IOException {
        deleteTree(jobDir(kosUid));
    }

    /** Mark a request as completed, then remove its job. */
    public void complete(String kosUid) throws IOException {
        writeDurably(doneDir().resolve(jobDir(kosUid).getFileName()), Long.toString(System.currentTimeMillis())
                .getBytes(StandardCharsets.US_ASCII));
        deleteTree(jobDir(kosUid));
    }

    /** Forget completed requests older than {@code days}. */
    public void purgeDone(int days) throws IOException {
        long limit = System.currentTimeMillis() - days * 86_400_000L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(doneDir())) {
            for (Path p : ds) if (Files.getLastModifiedTime(p).toMillis() < limit) Files.deleteIfExists(p);
        }
    }

    public void requestRetry(String kosUid) throws IOException {
        Files.writeString(jobDir(kosUid).resolve("retry.request"), Long.toString(System.currentTimeMillis()));
    }

    public boolean takeRetryRequest(String kosUid) throws IOException {
        return Files.deleteIfExists(jobDir(kosUid).resolve("retry.request"));
    }

    public Path newIncomingFile() {
        return incomingDir().resolve(UUID.randomUUID() + ".part");
    }

    public long freeBytes() throws IOException {
        return Files.getFileStore(root).getUsableSpace();
    }

    /** Remove half-finished downloads and job directories of an unclean shutdown. */
    public void cleanTransient() throws IOException {
        deleteTree(incomingDir());
        Files.createDirectories(incomingDir());
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(jobsDir(), ".*")) {
            for (Path p : ds) deleteTree(p);
        }
    }

    static void writeDurably(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(content));
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeProps(Path target, Properties p) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream out = Channels.newOutputStream(ch)) {
            p.store(out, "aifi-pull-agent job state");
            out.flush();
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path p : all) Files.deleteIfExists(p);
        }
    }
}
