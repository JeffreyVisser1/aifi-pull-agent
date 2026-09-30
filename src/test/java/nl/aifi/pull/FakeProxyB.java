package nl.aifi.pull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for the receiving DICOM Web Proxy (B) over real HTTPS: OAuth2 token endpoint,
 * authenticated health probe and WADO-RS series / instance retrieve as multipart/related.
 */
final class FakeProxyB implements AutoCloseable {

    static final String CLIENT_ID = "pull-agent";
    static final String CLIENT_SECRET = "pull-s3cret";

    private final HttpsServer server;
    /** SOP Instance UID → (study, series, Part-10 bytes). */
    private final Map<String, Object[]> instances = new ConcurrentHashMap<>();
    /** Instances that are "not yet at proxy A": not served until released. */
    final Set<String> withheld = ConcurrentHashMap.newKeySet();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger tokensIssued = new AtomicInteger();

    FakeProxyB() throws Exception {
        SSLContext ssl = Tls.context(Tls.keyManagers(TestPki.p12("node"), TestPki.PASSWORD), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(new SSLParameters(Tls.cipherSuites(), Tls.PROTOCOLS));
            }
        });
        server.createContext("/auth/token", this::token);
        server.createContext("/dicom-web/health", ex -> send(ex, authorized(ex) ? 200 : 401, "application/json", "{}".getBytes()));
        server.createContext("/dicom-web/studies", this::retrieve);
        server.start();
    }

    String baseUrl() {
        return "https://127.0.0.1:" + server.getAddress().getPort() + "/dicom-web";
    }

    void add(Attributes ds) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(b, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds);
        }
        instances.put(ds.getString(Tag.SOPInstanceUID),
                new Object[] {ds.getString(Tag.StudyInstanceUID), ds.getString(Tag.SeriesInstanceUID), b.toByteArray()});
    }

    private void token(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!body.contains("client_id=" + CLIENT_ID) || !body.contains("client_secret=" + CLIENT_SECRET)) {
            send(ex, 401, "application/json", "{\"error\":\"invalid_client\"}".getBytes());
            return;
        }
        int n = tokensIssued.incrementAndGet();
        send(ex, 200, "application/json", ("{\"access_token\":\"jwt-" + n + "\",\"expires_in\":3600}").getBytes());
    }

    private boolean authorized(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        return a != null && a.startsWith("Bearer jwt-");
    }

    private void retrieve(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { send(ex, 401, "application/json", "{}".getBytes()); return; }
        String path = ex.getRequestURI().getPath();
        requests.add(path);
        String[] p = path.split("/");            // "", dicom-web, studies, {st}, series, {se}[, instances, {sop}]
        if (p.length < 6 || !"series".equals(p[4])) { send(ex, 400, "text/plain", new byte[0]); return; }
        String st = p[3];
        String se = p[5];
        String sop = p.length >= 8 ? p[7] : null;
        List<byte[]> parts = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : instances.entrySet()) {
            Object[] v = e.getValue();
            if (!st.equals(v[0]) || !se.equals(v[1]) || withheld.contains(e.getKey())) continue;
            if (sop != null && !sop.equals(e.getKey())) continue;
            parts.add((byte[]) v[2]);
        }
        if (parts.isEmpty()) { send(ex, 404, "application/json", "{}".getBytes()); return; }
        String boundary = "fakeb-" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("preamble to ignore\r\n".getBytes(StandardCharsets.US_ASCII));
        for (byte[] part : parts) {
            body.write(("--" + boundary + "\r\nContent-Type: application/dicom; transfer-syntax=" + UID.ExplicitVRLittleEndian
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.write(part);
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        send(ex, 200, "multipart/related; type=\"application/dicom\"; boundary=" + boundary, body.toByteArray());
    }

    private static void send(HttpExchange ex, int status, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
