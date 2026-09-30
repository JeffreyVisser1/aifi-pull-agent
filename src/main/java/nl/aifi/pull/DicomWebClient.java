package nl.aifi.pull;

import javax.net.ssl.SSLParameters;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * WADO-RS client for the receiving DICOM Web Proxy. Same transport policy as the gateway's
 * STOW client: HTTPS only, TLS 1.3/1.2 AEAD suites, certificate and host name verified, no
 * system proxy, no redirects. Authentication: OAuth2 client credentials (JWT, cached, renewed
 * once on a 401) or an X-Api-Key header. Secrets and tokens are never logged.
 */
public final class DicomWebClient {

    private static final Logger LOG = Logger.getLogger(DicomWebClient.class.getName());
    private static final String ACCEPT_ANY_TS = "multipart/related; type=\"application/dicom\"; transfer-syntax=*";
    private static final String ACCEPT_PLAIN = "multipart/related; type=\"application/dicom\"";

    /** Receives each retrieved part as a file; the file may be moved away by the sink. */
    public interface PartSink {
        void part(Path file) throws IOException;
    }

    /** Thrown for a transport or authentication failure (retryable). */
    public static final class SourceException extends IOException {
        public SourceException(String message, Throwable cause) { super(message, cause); }
    }

    private final Config.Source cfg;
    private final HttpClient http;
    private final String base;
    private final String tokenUrl;
    private final JobStore store;
    private String token;
    private long tokenExpiresAt;

    public DicomWebClient(Config.Source cfg, JobStore store) throws IOException {
        this.cfg = cfg;
        this.store = store;
        String b = cfg.baseUrl.trim();
        if (!b.toLowerCase(Locale.ROOT).startsWith("https://")) throw new IOException("source.baseUrl must start with https://");
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        URI u = URI.create(b);
        this.tokenUrl = cfg.tokenEndpoint.isBlank() ? u.getScheme() + "://" + u.getRawAuthority() + "/auth/token"
                                                    : cfg.tokenEndpoint.trim();
        try {
            SSLParameters params = new SSLParameters(Tls.cipherSuites(), Tls.PROTOCOLS);
            params.setEndpointIdentificationAlgorithm("HTTPS");
            this.http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .sslContext(Tls.context(Tls.keyManagers(cfg.clientKeystorePath, cfg.clientKeystorePassword),
                            Tls.trustManagers(cfg.trustCertPath)))
                    .sslParameters(params)
                    .build();
        } catch (Exception e) {
            throw new IOException("Cannot set up TLS for the source: " + e.getMessage(), e);
        }
    }

    public String base() { return base; }

    /**
     * WADO-RS retrieve of {@code path} (e.g. {@code /studies/{s}/series/{se}}); every part is
     * handed to {@code sink} as a file.
     *
     * @return the HTTP status (200 = parts delivered; 204/404 = nothing there (yet))
     */
    public int retrieve(String path, PartSink sink) throws IOException {
        boolean renewed = false;
        String accept = ACCEPT_ANY_TS;
        while (true) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofMillis(cfg.responseTimeoutMs))
                    .header("Accept", accept)
                    .GET();
            auth(b);
            HttpResponse<InputStream> r = send(b.build());
            int st = r.statusCode();
            if (st == 401 && "OAUTH2".equalsIgnoreCase(cfg.authMode) && !renewed) {
                drain(r);
                invalidateToken();
                renewed = true;
                continue;
            }
            if (st == 406 && accept.equals(ACCEPT_ANY_TS)) {        // proxy does not take transfer-syntax=*
                drain(r);
                accept = ACCEPT_PLAIN;
                continue;
            }
            if (st != 200) {
                drain(r);
                return st;
            }
            String ct = r.headers().firstValue("Content-Type").orElse("");
            String boundary = MultipartReader.boundary(ct);
            if (!ct.toLowerCase(Locale.ROOT).startsWith("multipart/related") || boundary == null) {
                drain(r);
                throw new IOException("unexpected response type from the source: " + ct);
            }
            try (InputStream in = r.body()) {
                MultipartReader mr = new MultipartReader(in, boundary);
                while (true) {
                    Path tmp = store.newIncomingFile();
                    Map<String, String> headers;
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
                        headers = mr.next(out);
                    } catch (IOException e) {
                        Files.deleteIfExists(tmp);
                        throw new SourceException("retrieve interrupted: " + e.getMessage(), e);
                    }
                    if (headers == null) {
                        Files.deleteIfExists(tmp);
                        break;
                    }
                    try {
                        sink.part(tmp);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }
            }
            return 200;
        }
    }

    /** Connection check: token (OAUTH2) and the proxy's authenticated health probe. */
    public String check() throws IOException {
        invalidateToken();
        URI u = URI.create(base);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(u.getScheme() + "://" + u.getRawAuthority() + "/dicom-web/health"))
                .timeout(Duration.ofMillis(cfg.connectTimeoutMs + 10_000L)).GET();
        auth(b);
        HttpResponse<InputStream> r = send(b.build());
        drain(r);
        if (r.statusCode() / 100 != 2) {
            throw new IOException("GET /dicom-web/health returned HTTP " + r.statusCode() + " " + hint(r.statusCode()));
        }
        return ("OAUTH2".equalsIgnoreCase(cfg.authMode) ? "token obtained from " + tokenUrl + "; " : "API key accepted; ")
                + "GET /dicom-web/health -> HTTP " + r.statusCode();
    }

    static String hint(int status) {
        switch (status) {
            case 401: return "(credential refused - check source.clientId/clientSecret or source.apiKey)";
            case 403: return "(refused - is this machine's IP in auth.wadoAllowedCidrs of the proxy?)";
            case 404: return "(not found - not (yet) available at the sending side, or baseUrl does not end in /dicom-web)";
            case 406: return "(the proxy refused the requested media type)";
            case 503: return "(the proxy cannot reach its upstream - check wadoPassthrough on the proxy)";
            default: return "";
        }
    }

    // ── auth ─────────────────────────────────────────────────────────────────

    private void auth(HttpRequest.Builder b) throws IOException {
        if ("API_KEY".equalsIgnoreCase(cfg.authMode)) b.header("X-Api-Key", cfg.apiKey);
        else b.header("Authorization", "Bearer " + token());
    }

    private synchronized void invalidateToken() {
        token = null;
    }

    private synchronized String token() throws IOException {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpiresAt) return token;
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(cfg.clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(cfg.clientSecret, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(cfg.connectTimeoutMs + 10_000L))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<InputStream> r = send(req);
        String body;
        try (InputStream in = r.body()) {
            body = readCapped(in);
        }
        if (r.statusCode() != 200) {
            String why = r.statusCode() == 401 ? "invalid_client - clientId/clientSecret do not match an enabled client on the proxy"
                    : r.statusCode() == 429 ? "the proxy is throttling token requests" : "unexpected answer";
            throw new SourceException("Token request to " + tokenUrl + " returned HTTP " + r.statusCode() + ": " + why, null);
        }
        Object tok;
        Object exp;
        try {
            Map<String, Object> m = Json.parseObject(body);
            tok = m.get("access_token");
            exp = m.get("expires_in");
        } catch (IllegalArgumentException e) {
            throw new SourceException("Token response from " + tokenUrl + " is not JSON", e);
        }
        if (!(tok instanceof String) || ((String) tok).isEmpty()) {
            throw new SourceException("Token response from " + tokenUrl + " has no access_token", null);
        }
        long expiresIn = exp instanceof Number ? ((Number) exp).longValue() : 300;
        token = (String) tok;
        tokenExpiresAt = now + Math.max(10, expiresIn) * 800;
        LOG.fine("OAuth2 token obtained from " + tokenUrl);
        return token;
    }

    // ── transport ────────────────────────────────────────────────────────────

    private HttpResponse<InputStream> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new SourceException(transportHint(req.uri(), e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while talking to the source", e);
        }
    }

    private static void drain(HttpResponse<InputStream> r) {
        try (InputStream in = r.body()) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignore) {
            // connection is closed anyway
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > 1 << 20) throw new IOException("token response exceeds 1 MiB");
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String transportHint(URI uri, IOException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = (m + " " + e.getClass().getSimpleName()).toLowerCase(Locale.ROOT);
        String hint = "";
        if (lower.contains("pkix") || lower.contains("certification path") || lower.contains("unable to find valid")) {
            hint = " - the proxy's certificate is not trusted: set source.trustCertPath to its certificate or CA.";
        } else if (lower.contains("no subject alternative") || lower.contains("no name matching")) {
            hint = " - the certificate does not name the host in source.baseUrl: use a name or IP address that is in the certificate.";
        } else if (lower.contains("connect timed out") || lower.contains("httpconnecttimeout")) {
            hint = " - no TCP connection or TLS handshake within source.connectTimeoutMs: firewall, wrong address, or the port does not speak TLS.";
        } else if (lower.contains("handshake") || lower.contains("protocol_version")) {
            hint = " - TLS handshake failed: the proxy must offer TLS 1.2 or 1.3.";
        } else if (lower.contains("connection refused")) {
            hint = " - nothing listens there: is the proxy running and is this its web (https) port?";
        } else if (lower.contains("timed out")) {
            hint = " - no answer in time: raise source.responseTimeoutMs for very large series.";
        }
        return "Connection to " + uri.getScheme() + "://" + uri.getRawAuthority() + " failed: " + m + hint;
    }
}
