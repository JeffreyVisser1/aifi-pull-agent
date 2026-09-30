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

    @TempDir Path tmp;
    private FakeProxyB proxy;
    private Dicom.CapturingScp dest;
    private Agent agent;
    private Config cfg;

    @BeforeEach
    void setUp() throws Exception {
        proxy = new FakeProxyB();
        dest = new Dicom.CapturingScp("AI_PACS");
        for (int i = 1; i <= 4; i++) proxy.add(Dicom.ct(STUDY, SERIES, SERIES + "." + i));   // .4 is not in the KOS
        cfg = new Config();
        cfg.listener.bindAddress = "127.0.0.1";
        cfg.listener.port = Dicom.freePort();
        cfg.listener.allowedCallingAeTitles = List.of("JIVEX_ROUTE");
        cfg.source.baseUrl = proxy.baseUrl();
        cfg.source.clientId = FakeProxyB.CLIENT_ID;
        cfg.source.clientSecret = FakeProxyB.CLIENT_SECRET;
        cfg.source.trustCertPath = TestPki.pem("node");
        cfg.destinations.add(destination("ai-pacs", "*", dest.port, "AI_PACS"));
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

    private static Attributes kos(String route, String... sops) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(SERIES, List.of(sops));
        return Dicom.kos(KOS, route, STUDY, m);
    }

    private Set<String> received(Dicom.CapturingScp scp) {
        return scp.received.stream().map(a -> a.getString(Tag.SOPInstanceUID)).collect(Collectors.toSet());
    }

    private boolean jobGone() {
        return !Files.exists(tmp.resolve("spool/jobs/" + KOS));
    }

    @Test
    void pullsExactlyTheListedInstancesAndStoresThem() throws Exception {
        start();
        assertEquals(0, Dicom.send(cfg.listener.port, "AIFIPULL", kos("main", SERIES + ".1", SERIES + ".2", SERIES + ".3")));

        await(this::jobGone, "job done");
        assertEquals(Set.of(SERIES + ".1", SERIES + ".2", SERIES + ".3"), received(dest), "only the instances in the KOS");
        assertEquals(3, dest.received.size(), "each once");
        assertEquals(List.of("/dicom-web/studies/" + STUDY + "/series/" + SERIES), proxy.requests, "one series retrieve");
        assertEquals(1, proxy.tokensIssued.get());
    }

    @Test
    void waitsForInstancesNotYetAvailableAndFetchesOnlyThose() throws Exception {
        proxy.withheld.add(SERIES + ".3");                   // not yet at proxy A
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main", SERIES + ".1", SERIES + ".2", SERIES + ".3"));

        await(() -> dest.received.size() == 2, "the available instances are stored right away");
        await(() -> {
            try { return JobStore.status(agent.store().readState(KOS)) == JobStore.Status.RETRY_WAIT; } catch (Exception e) { return false; }
        }, "job waits");
        Properties st = agent.store().readState(KOS);
        assertTrue(st.getProperty("lastError").contains("not (yet) available"), st.getProperty("lastError"));

        proxy.withheld.clear();
        await(this::jobGone, "job done after the instance appeared");
        assertEquals(3, dest.received.size(), "no instance stored twice");
        assertTrue(proxy.requests.contains("/dicom-web/studies/" + STUDY + "/series/" + SERIES + "/instances/" + SERIES + ".3"),
                "only the missing instance is fetched again: " + proxy.requests);
    }

    @Test
    void routeSelectsTheDestination() throws Exception {
        try (Dicom.CapturingScp thorax = new Dicom.CapturingScp("THORAX_AI")) {
            cfg.destinations.add(destination("thorax-ai", "ai-thorax", thorax.port, "THORAX_AI"));
            start();
            Dicom.send(cfg.listener.port, "AIFIPULL", kos("ai-thorax", SERIES + ".1"));
            await(this::jobGone, "job done");
            assertEquals(Set.of(SERIES + ".1"), received(thorax));
            assertTrue(dest.received.isEmpty(), "the catch-all destination got nothing");
        }
    }

    @Test
    void unknownRouteWaitsWithAClearMessage() throws Exception {
        cfg.destinations.get(0).route = "ai-mamma";
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("ai-thorax", SERIES + ".1"));
        await(() -> {
            try { return agent.store().readState(KOS).getProperty("lastError", "").contains("no destination configured for route 'ai-thorax'"); }
            catch (Exception e) { return false; }
        }, "job explains the missing destination");
        assertTrue(proxy.requests.isEmpty(), "nothing retrieved without a destination");
    }

    @Test
    void aResentKosIsOneJob() throws Exception {
        start();
        Attributes k = kos("main", SERIES + ".1");
        assertEquals(0, Dicom.send(cfg.listener.port, "AIFIPULL", k));
        await(this::jobGone, "job done");
        assertEquals(0, Dicom.send(cfg.listener.port, "AIFIPULL", k), "acknowledged again");
        Thread.sleep(1500);
        assertEquals(1, proxy.requests.size(), "a completed request is not pulled again: " + proxy.requests);
        assertEquals(1, dest.received.size(), "no duplicate at the destination");
        assertTrue(jobGone());
    }

    @Test
    void onlyKosDocumentsAreAccepted() throws Exception {
        start();
        int status = Dicom.send(cfg.listener.port, "AIFIPULL", Dicom.ct(STUDY, SERIES, SERIES + ".1"));
        assertEquals(-1, status, "CT image refused at association level");
        assertTrue(Files.list(tmp.resolve("spool/jobs")).findAny().isEmpty());
    }

    @Test
    void retrievedInstancesSurviveARestartWhileTheDestinationIsDown() throws Exception {
        int port = dest.port;
        dest.close();                                        // destination down
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main", SERIES + ".1", SERIES + ".2"));
        await(() -> {
            try { return Files.list(agent.store().instancesDir(KOS)).count() == 2; } catch (Exception e) { return false; }
        }, "retrieved and kept on disk");
        agent.close();
        dest = new Dicom.CapturingScp("AI_PACS", port);
        agent = new Agent(cfg);
        agent.start();
        await(this::jobGone, "delivered after the restart");
        assertEquals(2, dest.received.size());
        assertEquals(1, proxy.requests.size(), "not retrieved again: " + proxy.requests);
    }

    @Test
    void wrongSecretIsExplained() throws Exception {
        cfg.source.clientSecret = "wrong";
        start();
        Dicom.send(cfg.listener.port, "AIFIPULL", kos("main", SERIES + ".1"));
        await(() -> {
            try { return agent.store().readState(KOS).getProperty("lastError", "").contains("invalid_client"); } catch (Exception e) { return false; }
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
