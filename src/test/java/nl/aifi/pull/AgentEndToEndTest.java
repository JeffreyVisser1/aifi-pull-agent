package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** KOS in → WADO-RS through a stand-in proxy B over HTTPS → C-STORE to a stand-in destination. */
class AgentEndToEndTest {

    private static final String STUDY = "1.2.276.0.50.10528480.77";
    private static final String SERIES = STUDY + ".1";
    private static final String KOS = "2.25.7700001";
    private static final String STUDY_PATH = "/dicom-web/studies/" + STUDY;

    @TempDir Path tmp;
    private FakeProxyB proxy;
    private Dicom.CapturingScp dest;
    private Agent agent;
    private Config cfg;

    @BeforeEach
    void setUp() throws Exception {
        proxy = new FakeProxyB();
        dest = new Dicom.CapturingScp("AI_PACS");
        for (int i = 1; i <= 3; i++) proxy.add(Dicom.ct(STUDY, SERIES, SERIES + "." + i, "ai-thorax"));
        proxy.add(Dicom.ct("1.2.276.0.50.10528480.78", "1.2.276.0.50.10528480.78.1", "1.2.276.0.50.10528480.78.1.1", "main"));
        cfg = new Config();
        cfg.listener.bindAddress = "127.0.0.1";
        cfg.listener.port = Dicom.freePort();
        cfg.listener.allowedCallingAeTitles = List.of("JIVEX_ROUTE");
        cfg.source.baseUrl = proxy.baseUrl();
        cfg.source.clientId = FakeProxyB.CLIENT_ID;
        cfg.source.clientSecret = FakeProxyB.CLIENT_SECRET;
        cfg.source.trustCertPath = TestPki.pem("node");
        cfg.destinations.add(destination("ai-pacs", "*", dest.port, "AI_PACS"));
        cfg.retry.firstAttemptDelaySeconds = 0;
        cfg.retry.initialDelaySeconds = 1;
        cfg.spool.dir = tmp.resolve("spool").toString();
        cfg.spool.minFreeMb = 1;
    }

    private static Config.Destination destination(String name, String route, int port, String aet) {
        Config.Destination d = new Config.Destination();
        d.name = name;
        d.route = route;
        d.host = "127.0.0.1";
        d.port = port;
        d.aeTitle = aet;
        return d;
    }

    @AfterEach
    void tearDown() {
        if (agent != null) agent.close();
        dest.close();
        proxy.close();
    }

    private void start() throws Exception {
        assertEquals(List.of(), cfg.errors());
        agent = new Agent(cfg);
        agent.start();
    }

    /** The KOS for the three instances; {@code route == null}: as JiveX forwards it, without the route. */
    private static Attributes kos(String route) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(SERIES, List.of("2.25.1", "2.25.2", "2.25.3"));   // JiveX re-maps these UIDs; only the count matters
        return Dicom.kos(KOS, route, STUDY, m);
    }

    private Set<String> received(Dicom.CapturingScp scp) {
        return scp.received.stream().map(a -> a.getString(Tag.SOPInstanceUID)).collect(Collectors.toSet());
    }

    private boolean jobGone() {
        return !Files.exists(tmp.resolve("spool/jobs/" + KOS));
    }

    private Properties state() throws Exception {
        return agent.store().readState(KOS);
    }

    @Test
    void pullsTheWholeStudyAndStoresIt() throws Exception {
        start();
        assertEquals(0, Dicom.send(cfg.listener.port, "AIFIPULL", kos("main")));

        await(this::jobGone, "job done");
        assertEquals(Set.of(SERIES + ".1", SERIES + ".2", SERIES + ".3"), received(dest), "the study, nothing else");
        assertEquals(3, dest.received.size(), "each once");
        assertEquals(List.of(STUDY_PATH), proxy.requests, "one study retrieve");
        assertEquals(1, proxy.tokensIssued.get());
    }

    @Test
    void waitsUntilTheGatewayOffersTheStudy() throws Exception {
        for (int i = 1; i <= 3; i++) proxy.withheld.add(SERIES + "." + i);   // not yet in the gateway pool
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main"));

        await(() -> {
            try { return JobStore.status(state()) == JobStore.Status.RETRY_WAIT; } catch (Exception e) { return false; }
        }, "job waits");
        assertTrue(state().getProperty("lastError").contains("not (yet) available"), state().getProperty("lastError"));

        proxy.withheld.clear();
        await(this::jobGone, "job done once the study is there");
        assertEquals(3, dest.received.size());
    }

    @Test
    void fewerInstancesThanAnnouncedAreRetrievedAgainWithoutDuplicates() throws Exception {
        proxy.withheld.add(SERIES + ".3");
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main"));

        await(() -> dest.received.size() == 2, "what came back is stored right away");
        await(() -> {
            try { return state().getProperty("lastError", "").contains("only 2 of 3"); } catch (Exception e) { return false; }
        }, "job explains what is missing");

        proxy.withheld.clear();
        await(this::jobGone, "job done after the second retrieve");
        assertEquals(3, dest.received.size(), "no instance stored twice");
        assertEquals(Set.of(SERIES + ".1", SERIES + ".2", SERIES + ".3"), received(dest));
    }

    @Test
    void routeInTheKosSelectsTheDestination() throws Exception {
        try (Dicom.CapturingScp mamma = new Dicom.CapturingScp("MAMMA_AI")) {
            cfg.destinations.add(destination("mamma-ai", "ai-mamma", mamma.port, "MAMMA_AI"));
            start();
            Dicom.send(cfg.listener.port, "AIFIPULL", kos("ai-mamma"));
            await(this::jobGone, "job done");
            assertEquals(3, mamma.received.size(), "the KOS route wins over the route in the instances");
            assertTrue(dest.received.isEmpty(), "the catch-all destination got nothing");
        }
    }

    @Test
    void routeIsReadFromTheInstancesWhenJivexRemovedItFromTheKos() throws Exception {
        try (Dicom.CapturingScp thorax = new Dicom.CapturingScp("THORAX_AI")) {
            cfg.destinations.add(destination("thorax-ai", "ai-thorax", thorax.port, "THORAX_AI"));
            start();
            Dicom.send(cfg.listener.port, "AIFIPULL", kos(null));
            await(this::jobGone, "job done");
            assertEquals(3, thorax.received.size());
            assertTrue(dest.received.isEmpty());
        }
    }

    @Test
    void unknownRouteKeepsTheInstancesAndSaysWhy() throws Exception {
        cfg.destinations.get(0).route = "ai-mamma";
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("ai-thorax"));
        await(() -> {
            try { return state().getProperty("lastError", "").contains("no destination configured for route 'ai-thorax'"); }
            catch (Exception e) { return false; }
        }, "job explains the missing destination");
        assertEquals(3, Files.list(agent.store().instancesDir(KOS)).count(), "retrieved instances kept for later");
    }

    @Test
    void aResentKosIsOneJob() throws Exception {
        start();
        Attributes k = kos("main");
        assertEquals(0, Dicom.send(cfg.listener.port, "AIFIPULL", k));
        await(this::jobGone, "job done");
        assertEquals(0, Dicom.send(cfg.listener.port, "AIFIPULL", k), "acknowledged again");
        Thread.sleep(1500);
        assertEquals(1, proxy.requests.size(), "a completed request is not pulled again: " + proxy.requests);
        assertEquals(3, dest.received.size(), "no duplicate at the destination");
        assertTrue(jobGone());
    }

    @Test
    void onlyAifiKosDocumentsAreAccepted() throws Exception {
        start();
        int status = Dicom.send(cfg.listener.port, "AIFIPULL", Dicom.ct(STUDY, SERIES, SERIES + ".1"));
        assertEquals(-1, status, "CT image refused at association level");
        Attributes foreign = kos(null);
        foreign.remove(Tag.Manufacturer);                    // a key-image note from a radiologist, say
        assertEquals(0xC000, Dicom.send(cfg.listener.port, "AIFIPULL", foreign), "KOS without AIFI marks refused");
        assertTrue(Files.list(tmp.resolve("spool/jobs")).findAny().isEmpty());
    }

    @Test
    void retrievedInstancesSurviveARestartWhileTheDestinationIsDown() throws Exception {
        int port = dest.port;
        dest.close();                                        // destination down
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main"));
        await(() -> {
            try { return Files.list(agent.store().instancesDir(KOS)).count() == 3; } catch (Exception e) { return false; }
        }, "retrieved and kept on disk");
        agent.close();
        dest = new Dicom.CapturingScp("AI_PACS", port);
        agent = new Agent(cfg);
        agent.start();
        await(this::jobGone, "delivered after the restart");
        assertEquals(3, dest.received.size());
        assertEquals(1, proxy.requests.size(), "not retrieved again: " + proxy.requests);
    }

    @Test
    void wrongSecretIsExplained() throws Exception {
        cfg.source.clientSecret = "wrong";
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main"));
        await(() -> {
            try { return state().getProperty("lastError", "").contains("invalid_client"); } catch (Exception e) { return false; }
        }, "invalid_client in the job");
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for: " + what);
    }
}
