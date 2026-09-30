package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Test helpers: pseudonymized CT instances, a KOS like the gateway builds, a sender and a capturing SCP. */
final class Dicom {

    private Dicom() {}

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    static Attributes ct(String study, String series, String sop) {
        Attributes a = new Attributes();
        a.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
        a.setString(Tag.SOPInstanceUID, VR.UI, sop);
        a.setString(Tag.StudyInstanceUID, VR.UI, study);
        a.setString(Tag.SeriesInstanceUID, VR.UI, series);
        a.setString(Tag.PatientID, VR.LO, "PSEUDO1");
        a.setString(Tag.PatientName, VR.PN, "Anonymized Person");
        a.setString(Tag.Modality, VR.CS, "CT");
        a.setInt(Tag.Rows, VR.US, 4);
        a.setInt(Tag.Columns, VR.US, 4);
        a.setInt(Tag.BitsAllocated, VR.US, 16);
        a.setInt(Tag.BitsStored, VR.US, 12);
        a.setInt(Tag.HighBit, VR.US, 11);
        a.setInt(Tag.PixelRepresentation, VR.US, 0);
        a.setInt(Tag.SamplesPerPixel, VR.US, 1);
        a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
        a.setBytes(Tag.PixelData, VR.OW, new byte[32]);
        return a;
    }

    /** A "study ready" KOS as the AIFI gateway 1.2 writes it. */
    static Attributes kos(String kosUid, String route, String study, Map<String, List<String>> seriesToSops) {
        Attributes k = new Attributes();
        k.setString(Tag.SOPClassUID, VR.UI, UID.KeyObjectSelectionDocumentStorage);
        k.setString(Tag.SOPInstanceUID, VR.UI, kosUid);
        k.setString(Tag.StudyInstanceUID, VR.UI, study);
        k.setString(Tag.SeriesInstanceUID, VR.UI, kosUid + ".1");
        k.setString(Tag.PatientID, VR.LO, "PSEUDO1");
        k.setString(Tag.Modality, VR.CS, "KO");
        Sequence ev = k.newSequence(Tag.CurrentRequestedProcedureEvidenceSequence, 1);
        Attributes st = new Attributes();
        st.setString(Tag.StudyInstanceUID, VR.UI, study);
        Sequence ser = st.newSequence(Tag.ReferencedSeriesSequence, seriesToSops.size());
        int n = 0;
        for (Map.Entry<String, List<String>> e : seriesToSops.entrySet()) {
            Attributes se = new Attributes();
            se.setString(Tag.SeriesInstanceUID, VR.UI, e.getKey());
            Sequence refs = se.newSequence(Tag.ReferencedSOPSequence, e.getValue().size());
            for (String sop : e.getValue()) {
                Attributes r = new Attributes();
                r.setString(Tag.ReferencedSOPClassUID, VR.UI, UID.CTImageStorage);
                r.setString(Tag.ReferencedSOPInstanceUID, VR.UI, sop);
                refs.add(r);
                n++;
            }
            ser.add(se);
        }
        ev.add(st);
        k.setString(Tag.ValueType, VR.CS, "CONTAINER");
        Sequence content = k.newSequence(Tag.ContentSequence, 1);
        Attributes text = new Attributes();
        text.setString(Tag.RelationshipType, VR.CS, "CONTAINS");
        text.setString(Tag.ValueType, VR.CS, "TEXT");
        text.setString(Tag.TextValue, VR.UT, "AIFI route=" + route + "; instances=" + n + "; series=" + seriesToSops.size());
        content.add(text);
        return k;
    }

    /** C-STORE one object; returns the DIMSE status, or throws when the association is refused. */
    static int send(int port, String aet, Attributes ds) throws Exception {
        Device device = new Device("test-scu");
        ExecutorService exec = Executors.newCachedThreadPool();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(exec);
        device.setScheduledExecutor(sched);
        Connection local = new Connection();
        ApplicationEntity ae = new ApplicationEntity("JIVEX_ROUTE");
        ae.addConnection(local);
        device.addConnection(local);
        device.addApplicationEntity(ae);
        String cuid = ds.getString(Tag.SOPClassUID);
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCallingAET("JIVEX_ROUTE");
        rq.setCalledAET(aet);
        rq.addPresentationContext(new PresentationContext(1, cuid, UID.ExplicitVRLittleEndian));
        try {
            Association as = ae.connect(local, new Connection(null, "127.0.0.1", port), rq);
            int[] status = {-1};
            try {
                if (!as.getTransferSyntaxesFor(cuid).isEmpty()) {
                    as.cstore(cuid, ds.getString(Tag.SOPInstanceUID), Priority.NORMAL, new DataWriterAdapter(ds),
                            UID.ExplicitVRLittleEndian, new DimseRSPHandler(as.nextMessageID()) {
                                @Override
                                public void onDimseRSP(Association a, Attributes cmd, Attributes data) {
                                    super.onDimseRSP(a, cmd, data);
                                    status[0] = cmd.getInt(Tag.Status, -1);
                                }
                            });
                    as.waitForOutstandingRSP();
                }
            } finally {
                as.release();
                as.waitForSocketClose();
            }
            return status[0];
        } finally {
            exec.shutdownNow();
            sched.shutdownNow();
        }
    }

    /** Destination SCP that records every dataset it receives. */
    static final class CapturingScp implements AutoCloseable {
        final List<Attributes> received = new CopyOnWriteArrayList<>();
        final int port;
        private final Device device = new Device("capture");
        private final ExecutorService exec = Executors.newCachedThreadPool();
        private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();

        CapturingScp(String aet) throws Exception {
            this(aet, freePort());
        }

        CapturingScp(String aet, int port) throws Exception {
            this.port = port;
            ApplicationEntity ae = new ApplicationEntity(aet);
            ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"));
            Connection conn = new Connection(null, "127.0.0.1", port);
            DicomServiceRegistry reg = new DicomServiceRegistry();
            reg.addDicomService(new BasicCEchoSCP());
            reg.addDicomService(new BasicCStoreSCP("*") {
                @Override
                protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data,
                                     Attributes rsp) throws IOException {
                    received.add(data.readDataset(pc.getTransferSyntax()));
                }
            });
            ae.setDimseRQHandler(reg);
            device.addApplicationEntity(ae);
            device.addConnection(conn);
            ae.addConnection(conn);
            device.setExecutor(exec);
            device.setScheduledExecutor(sched);
            device.bindConnections();
        }

        @Override
        public void close() {
            device.unbindConnections();
            exec.shutdownNow();
            sched.shutdownNow();
        }
    }
}
