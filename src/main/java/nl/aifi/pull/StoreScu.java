package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** C-STOREs retrieved Part-10 files to a destination over one association. */
public final class StoreScu {

    private static final Set<String> UNCOMPRESSED = Set.of(
            UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian, UID.ExplicitVRBigEndian);

    private StoreScu() {}

    /**
     * @return SOP Instance UID → DIMSE status for every instance that got an answer
     *         (0x0000 or 0xB0xx = stored); instances without an answer are absent
     */
    public static Map<String, Integer> store(Config.Destination d, List<Path> files) throws Exception {
        Map<String, Set<String>> contexts = new LinkedHashMap<>();
        for (Path f : files) {
            try (DicomInputStream in = new DicomInputStream(f.toFile())) {
                Attributes fmi = in.readFileMetaInformation();
                if (fmi == null) throw new IOException("retrieved object without file meta information: " + f.getFileName());
                contexts.computeIfAbsent(fmi.getString(Tag.MediaStorageSOPClassUID), k -> new LinkedHashSet<>())
                        .add(fmi.getString(Tag.TransferSyntaxUID));
            }
        }
        AAssociateRQ rq = request(d);
        int pcid = 1;
        for (Map.Entry<String, Set<String>> e : contexts.entrySet()) {
            for (String ts : e.getValue()) {
                if (!UNCOMPRESSED.contains(ts)) rq.addPresentationContext(new PresentationContext(pcid += 2, e.getKey(), ts));
            }
            rq.addPresentationContext(new PresentationContext(pcid += 2, e.getKey(),
                    UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian));
            if (pcid > 250) throw new IOException("too many SOP class / transfer syntax combinations for one association");
        }
        Map<String, Integer> result = new ConcurrentHashMap<>();
        withAssociation(d, rq, as -> {
            for (Path f : files) {
                Attributes ds;
                String ts;
                try (DicomInputStream in = new DicomInputStream(f.toFile())) {
                    in.readFileMetaInformation();
                    ds = in.readDataset();
                    ts = in.getTransferSyntax();
                }
                String cuid = ds.getString(Tag.SOPClassUID);
                String iuid = ds.getString(Tag.SOPInstanceUID);
                List<String> accepted = new ArrayList<>(as.getTransferSyntaxesFor(cuid));
                String sendTs = accepted.contains(ts) ? ts : UNCOMPRESSED.contains(ts) && !accepted.isEmpty() ? accepted.get(0) : null;
                if (sendTs == null) {
                    result.put(iuid, 0x0122);                       // SOP class / transfer syntax not supported
                    continue;
                }
                as.cstore(cuid, iuid, Priority.NORMAL, new DataWriterAdapter(ds), sendTs, new DimseRSPHandler(as.nextMessageID()) {
                    @Override
                    public void onDimseRSP(Association a, Attributes cmd, Attributes data) {
                        super.onDimseRSP(a, cmd, data);
                        result.put(iuid, cmd.getInt(Tag.Status, -1));
                    }
                });
            }
            as.waitForOutstandingRSP();
            return 0;
        });
        return result;
    }

    public static boolean isStored(Integer status) {
        return status != null && (status == 0 || (status & 0xF000) == 0xB000);
    }

    /** C-ECHO a destination, for {@code check}. */
    public static int echo(Config.Destination d) throws Exception {
        AAssociateRQ rq = request(d);
        rq.addPresentationContext(new PresentationContext(1, UID.Verification, UID.ImplicitVRLittleEndian));
        return withAssociation(d, rq, as -> {
            DimseRSP rsp = as.cecho();
            rsp.next();
            return rsp.getCommand().getInt(Tag.Status, -1);
        });
    }

    private static AAssociateRQ request(Config.Destination d) {
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCallingAET(d.callingAeTitle);
        rq.setCalledAET(d.aeTitle);
        return rq;
    }

    private interface Action { int run(Association as) throws Exception; }

    private static int withAssociation(Config.Destination d, AAssociateRQ rq, Action action) throws Exception {
        Device device = new Device("aifi-pull-agent-scu");
        ExecutorService exec = Executors.newCachedThreadPool();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(exec);
        device.setScheduledExecutor(sched);
        Association as = null;
        try {
            Connection local = new Connection();
            local.setRequestTimeout(d.associationTimeoutMs);
            local.setReleaseTimeout(d.associationTimeoutMs);
            local.setResponseTimeout(d.responseTimeoutMs);
            Connection remote = new Connection(null, d.host, d.port);
            remote.setConnectTimeout(d.connectTimeoutMs);
            Tls.apply(device, local, d.tls, false);
            Tls.apply(device, remote, d.tls, false);
            ApplicationEntity ae = new ApplicationEntity(d.callingAeTitle);
            ae.addConnection(local);
            device.addConnection(local);
            device.addApplicationEntity(ae);
            as = ae.connect(local, remote, rq);
            return action.run(as);
        } finally {
            if (as != null) {
                try { as.release(); as.waitForSocketClose(); } catch (Exception ignore) { /* best effort */ }
            }
            exec.shutdownNow();
            sched.shutdownNow();
        }
    }
}
