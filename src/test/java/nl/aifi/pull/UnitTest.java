package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UnitTest {

    // ── KOS from the real gateway ────────────────────────────────────────────

    private static Attributes read(String resource) throws Exception {
        try (DicomInputStream in = new DicomInputStream(UnitTest.class.getResourceAsStream(resource))) {
            in.readFileMetaInformation();
            return in.readDataset();
        }
    }

    @Test
    void readsTheGatewayKosAfterJivexPseudonymizedIt() throws Exception {
        // gateway 2.0 KOS trigger, then the JiveX-style profile: Content Sequence and Series
        // Description removed, evidence UIDs re-mapped, study UID = JiveX pseudonym
        KosManifest m = KosManifest.parse(read("/jivex-forwarded-kos.dcm"));
        assertEquals("1.2.276.0.50.10528480.99", m.studyUid);
        assertEquals("", m.route, "removed by the profile: read from the instances instead");
        assertEquals(3, m.expected, "counted in the evidence sequence");
        assertTrue(m.fromAifi, "the Manufacturer survives");
    }

    @Test
    void readsTheKosOfGateway12() throws Exception {
        KosManifest m = KosManifest.parse(read("/gateway-1.2-kos.dcm"));
        assertEquals("1.2.276.0.50.10528480.99", m.studyUid);
        assertEquals("ai-thorax", m.route);
        assertEquals(3, m.expected);
        assertTrue(m.fromAifi);
    }

    @Test
    void routeFallsBackToTheSeriesDescription() {
        Attributes k = Dicom.kos("2.25.1", "ai-mamma", "1.2.3", Map.of("1.2.3.1", List.of("2.25.9")));
        k.remove(Tag.ContentSequence);
        KosManifest m = KosManifest.parse(k);
        assertEquals("ai-mamma", m.route);
        assertEquals(1, m.expected);
    }

    @Test
    void refusesObjectsThatAreNoPullRequest() {
        Attributes ct = Dicom.ct("1.2.3", "1.2.3.1", "1.2.3.1.1");
        assertThrows(IllegalArgumentException.class, () -> KosManifest.parse(ct));
        Attributes noStudy = Dicom.kos("2.25.1", "main", "1.2.3", Map.of("1.2.3.1", List.of("2.25.9")));
        noStudy.setString(Tag.StudyInstanceUID, org.dcm4che3.data.VR.UI, "../../etc");
        assertThrows(IllegalArgumentException.class, () -> KosManifest.parse(noStudy), "UIDs become file names: must be UIDs");
        Attributes badRoute = Dicom.kos("2.25.1", "AI Thorax/..", "1.2.3", Map.of("1.2.3.1", List.of("2.25.9")));
        assertThrows(IllegalArgumentException.class, () -> KosManifest.parse(badRoute));
        Attributes empty = Dicom.kos("2.25.1", null, "1.2.3", Map.of());
        assertEquals(0, KosManifest.parse(empty).expected, "no count: the whole study, however large");
    }

    // ── multipart ────────────────────────────────────────────────────────────

    private static List<byte[]> readAll(byte[] body, String boundary) throws Exception {
        MultipartReader r = new MultipartReader(new ByteArrayInputStream(body), boundary);
        List<byte[]> parts = new ArrayList<>();
        while (true) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Map<String, String> h = r.next(out);
            if (h == null) return parts;
            assertEquals("application/dicom", h.get("content-type"));
            parts.add(out.toByteArray());
        }
    }

    private static byte[] body(String boundary, byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("ignored preamble\r\n".getBytes(StandardCharsets.US_ASCII));
        for (byte[] p : parts) {
            b.writeBytes(("--" + boundary + "\r\nContent-Type: application/dicom\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            b.writeBytes(p);
            b.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        b.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return b.toByteArray();
    }

    @Test
    void multipartPartsComeOutByteExact() throws Exception {
        String boundary = "abcabd";
        // data full of near-misses of the delimiter "\r\n--abcabd"
        byte[] tricky = "x\r\n--abcab\r\n--abcabcabd\r\n-\r\n--ab".getBytes(StandardCharsets.US_ASCII);
        byte[] random = new byte[300_000];
        new Random(7).nextBytes(random);
        byte[] empty = new byte[0];
        List<byte[]> parts = readAll(body(boundary, tricky, random, empty), boundary);
        assertEquals(3, parts.size());
        assertArrayEquals(tricky, parts.get(0));
        assertArrayEquals(random, parts.get(1));
        assertArrayEquals(empty, parts.get(2));
    }

    @Test
    void truncatedMultipartIsAnError() {
        byte[] b = body("bnd", "data".getBytes());
        byte[] cut = java.util.Arrays.copyOf(b, b.length - 10);
        assertThrows(java.io.IOException.class, () -> readAll(cut, "bnd"));
    }

    @Test
    void boundaryIsReadFromTheContentType() {
        assertEquals("x-1", MultipartReader.boundary("multipart/related; type=\"application/dicom\"; boundary=x-1"));
        assertEquals("q q", MultipartReader.boundary("multipart/related; boundary=\"q q\"; type=\"application/dicom\""));
        assertNull(MultipartReader.boundary("application/json"));
    }

    // ── configuration ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Config defaults() throws Exception {
        try (InputStream in = UnitTest.class.getResourceAsStream("/default-pull-agent.yaml")) {
            return Config.fromMap((Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(in));
        }
    }

    @Test
    void defaultFileOnlyAsksForTheSiteSpecificValues() throws Exception {
        assertEquals(List.of("source.clientId is required", "source.clientSecret is required",
                "destinations.0.host is required", "destinations.0.aeTitle must be 1-16 characters, no backslash"),
                defaults().errors());
    }

    @Test
    void configMistakesAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Config.fromMap(Map.of("source", Map.of("baseUrll", "x"))));
        Config c = defaults();
        c.source.baseUrl = "http://10.8.0.20:8484/dicom-web";
        assertTrue(c.errors().stream().anyMatch(e -> e.startsWith("source.baseUrl must be an https://")));
    }

    @Test
    void routeChoosesItsDestinationWithCatchAll() throws Exception {
        Config c = defaults();
        Config.Destination thorax = new Config.Destination();
        thorax.name = "thorax";
        thorax.route = "ai-thorax";
        c.destinations.add(thorax);
        assertEquals("thorax", c.destinationFor("ai-thorax").name);
        assertEquals("ai-pacs", c.destinationFor("anything-else").name);
        c.destinations.get(0).route = "main";
        assertNull(c.destinationFor("anything-else"));
    }
}
