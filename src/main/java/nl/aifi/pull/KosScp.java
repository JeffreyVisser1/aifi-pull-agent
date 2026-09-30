package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * DICOM listener for the KOS pull requests (routed by JiveX). Only the Key Object Selection
 * Document Storage SOP class (and Verification) is accepted. A KOS is acknowledged with
 * Success only after its job is stored durably; a re-sent KOS is acknowledged again without
 * creating a second job.
 */
public final class KosScp {

    private static final Logger LOG = Logger.getLogger(KosScp.class.getName());

    /** Notified after a new job was stored. */
    public interface Listener {
        void onJob(KosManifest m);
    }

    private final Config.Listener cfg;
    private final long minFreeBytes;
    private final JobStore store;
    private final Listener listener;
    private final Device device = new Device("aifi-pull-agent");
    private final ExecutorService exec = Executors.newCachedThreadPool();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();

    public KosScp(Config.Listener cfg, long minFreeBytes, JobStore store, Listener listener) {
        this.cfg = cfg;
        this.minFreeBytes = minFreeBytes;
        this.store = store;
        this.listener = listener;
    }

    public void start() throws Exception {
        ApplicationEntity ae = new ApplicationEntity(cfg.aeTitle);
        ae.addTransferCapability(new TransferCapability(null, UID.KeyObjectSelectionDocumentStorage,
                TransferCapability.Role.SCP, UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian, UID.ExplicitVRBigEndian));
        ae.addTransferCapability(new TransferCapability(null, UID.Verification, TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        if (!cfg.allowedCallingAeTitles.isEmpty()) ae.setAcceptedCallingAETitles(cfg.allowedCallingAeTitles.toArray(new String[0]));
        Connection conn = new Connection();
        conn.setBindAddress(cfg.bindAddress);
        conn.setPort(cfg.port);
        Tls.apply(device, conn, cfg.tls, true);
        DicomServiceRegistry reg = new DicomServiceRegistry();
        reg.addDicomService(new BasicCEchoSCP());
        reg.addDicomService(new BasicCStoreSCP(UID.KeyObjectSelectionDocumentStorage) {
            @Override
            protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data,
                                 Attributes rsp) throws IOException {
                receive(as, pc, rq, data);
            }
        });
        ae.setDimseRQHandler(reg);
        device.addApplicationEntity(ae);
        device.addConnection(conn);
        ae.addConnection(conn);
        device.setExecutor(exec);
        device.setScheduledExecutor(sched);
        device.bindConnections();
        LOG.info("KOS listener started: AE=" + cfg.aeTitle + " " + cfg.bindAddress + ":" + cfg.port
                + (cfg.tls.enabled ? " TLS" : " (no TLS)")
                + (cfg.allowedCallingAeTitles.isEmpty() ? ", any caller" : ", callers " + cfg.allowedCallingAeTitles));
    }

    private void receive(Association as, PresentationContext pc, Attributes rq, PDVInputStream data) throws IOException {
        if (store.freeBytes() < minFreeBytes) {
            LOG.severe("Refusing KOS from " + as.getCallingAET() + ": less than spool.minFreeMb free");
            throw new DicomServiceException(Status.OutOfResources, "Pull agent spool disk full");
        }
        Attributes kos = data.readDataset(pc.getTransferSyntax());
        KosManifest m;
        try {
            m = KosManifest.parse(kos);
            if (!m.fromAifi && !cfg.acceptAnyKos) {
                throw new IllegalArgumentException("not a pull request of the AIFI gateway (no \"AIFI\" Manufacturer, "
                        + "Series Description or Key Object Description); set listener.acceptAnyKos if the PACS removes them");
            }
        } catch (IllegalArgumentException e) {
            LOG.warning("Refusing KOS from " + as.getCallingAET() + ": " + e.getMessage());
            throw new DicomServiceException(Status.CannotUnderstand, e.getMessage());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(bytes, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(kos.createFileMetaInformation(UID.ExplicitVRLittleEndian), kos);
        }
        boolean created;
        try {
            created = store.create(m, bytes.toByteArray());
        } catch (IOException e) {
            LOG.warning("Cannot store the pull request from " + as.getCallingAET() + ": " + e.getMessage());
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
        if (!created) {
            LOG.info("KOS " + m.kosUid + " received again from " + as.getCallingAET() + " - job already exists");
            return;
        }
        LOG.info("[" + (m.route.isEmpty() ? "?" : m.route) + "] pull request received from " + as.getCallingAET()
                + ": StudyInstanceUID " + m.studyUid + (m.expected > 0 ? ", " + m.expected + " instance(s)" : "")
                + (m.route.isEmpty() ? ", route read from the instances" : "") + " (job " + m.kosUid + ")");
        listener.onJob(m);
    }

    public void stop() {
        device.unbindConnections();
        exec.shutdownNow();
        sched.shutdownNow();
    }
}
