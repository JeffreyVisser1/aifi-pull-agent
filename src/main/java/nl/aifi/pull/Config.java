package nl.aifi.pull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The agent configuration ({@code config/pull-agent.yaml}). Read once at start-up; a change
 * needs a service restart. Unknown keys are refused so a typo never becomes a default.
 */
public final class Config {

    public Listener listener = new Listener();
    public Source source = new Source();
    public List<Destination> destinations = new ArrayList<>();
    public Retry retry = new Retry();
    public Spool spool = new Spool();
    public Logging logging = new Logging();
    /** Pull jobs processed in parallel. */
    public int workers = 2;

    /** Where the KOS notifications arrive (DICOM C-STORE). */
    public static final class Listener {
        public String bindAddress = "0.0.0.0";
        public int port = 11300;
        public String aeTitle = "AIFIPULL";
        public List<String> allowedCallingAeTitles = new ArrayList<>();
        public DicomTls tls = new DicomTls();
        /**
         * false: only KOS objects of the AIFI gateway are accepted (Manufacturer "AIFI ...",
         * Series Description or Key Object Description "AIFI route=..."); other KOS objects
         * that a PACS routing rule forwards by mistake are refused.
         */
        public boolean acceptAnyKos = false;
    }

    /** DICOM TLS (BCP 195); see the gateway manual. */
    public static final class DicomTls {
        public boolean enabled = false;
        public String keystorePath = "";
        public String keystorePassword = "";
        public String trustCertPath = "";
        public boolean requireClientCert = true;
        public boolean verifyHostname = true;
    }

    /** The DICOM Web Proxy the studies are retrieved through (WADO-RS). HTTPS only. */
    public static final class Source {
        public String baseUrl = "https://127.0.0.1:8484/dicom-web";
        /** OAUTH2 (client credentials, JWT) or API_KEY (X-Api-Key header). */
        public String authMode = "OAUTH2";
        /** Empty = {@code <origin of baseUrl>/auth/token}. */
        public String tokenEndpoint = "";
        public String clientId = "";
        public String clientSecret = "";
        public String apiKey = "";
        public String trustCertPath = "";
        public String clientKeystorePath = "";
        public String clientKeystorePassword = "";
        public int connectTimeoutMs = 10_000;
        public int responseTimeoutMs = 540_000;
        /** Not used since 1.1 (whole studies are retrieved); still accepted so older files load. */
        public int instanceFetchThreshold = 50;
    }

    /** Where the retrieved studies go (C-STORE), chosen by the route name in the KOS. */
    public static final class Destination {
        public String name = "";
        /** Route name from the KOS ("AIFI route=..."); "*" = every route without its own destination. */
        public String route = "*";
        public String host = "";
        public int port = 104;
        public String aeTitle = "";
        public String callingAeTitle = "AIFIPULL";
        public int connectTimeoutMs = 5_000;
        public int associationTimeoutMs = 30_000;
        public int responseTimeoutMs = 540_000;
        public DicomTls tls = new DicomTls();
    }

    public static final class Retry {
        /** Wait before the first retrieval: the gateway pseudonymizes the study after sending the KOS. */
        public int firstAttemptDelaySeconds = 10;
        /** Attempts before a job is parked as FAILED (0 = retry forever). */
        public int maxAttempts = 96;
        public int initialDelaySeconds = 30;
        public int maxDelaySeconds = 1_800;
    }

    public static final class Spool {
        public String dir = "spool";
        public long minFreeMb = 2_048;
        public int failedRetentionDays = 30;
    }

    public static final class Logging {
        public String dir = "logs";
        public String level = "INFO";
        public int maxFileMb = 20;
        public int maxFiles = 20;
        /** Kept for log compatibility with the gateway; the agent only sees pseudonymized identifiers. */
        public boolean revealIdentifiers = true;
    }

    /** The destination for a route: an exact match first, then "*". */
    public Destination destinationFor(String route) {
        for (Destination d : destinations) if (d.route.equals(route)) return d;
        for (Destination d : destinations) if ("*".equals(d.route)) return d;
        return null;
    }

    public List<String> secretPaths() {
        List<String> out = new ArrayList<>(List.of("listener.tls.keystorePassword", "source.clientSecret",
                "source.apiKey", "source.clientKeystorePassword"));
        for (int i = 0; i < destinations.size(); i++) out.add("destinations." + i + ".tls.keystorePassword");
        return out;
    }

    public List<String> pathSettings() {
        List<String> out = new ArrayList<>(List.of("spool.dir", "logging.dir", "listener.tls.keystorePath",
                "listener.tls.trustCertPath", "source.trustCertPath", "source.clientKeystorePath"));
        for (int i = 0; i < destinations.size(); i++) {
            out.add("destinations." + i + ".tls.keystorePath");
            out.add("destinations." + i + ".tls.trustCertPath");
        }
        return out;
    }

    // ── binding ──────────────────────────────────────────────────────────────

    public static Config fromMap(Map<String, Object> yaml) {
        Config c = new Config();
        if (yaml != null) bind(c, yaml, "");
        return c;
    }

    @SuppressWarnings("unchecked")
    private static void bind(Object target, Map<String, Object> map, String prefix) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String path = prefix + e.getKey();
            Field f;
            try {
                f = target.getClass().getField(e.getKey());
            } catch (NoSuchFieldException ex) {
                throw new IllegalArgumentException("Unknown setting '" + path + "'");
            }
            if (Modifier.isStatic(f.getModifiers())) throw new IllegalArgumentException("Unknown setting '" + path + "'");
            Object v = e.getValue();
            Class<?> t = f.getType();
            try {
                if (t == String.class) {
                    if (v instanceof Map || v instanceof List) throw new IllegalArgumentException("'" + path + "' must be a single value");
                    f.set(target, v == null ? "" : String.valueOf(v).trim());
                } else if (t == int.class) {
                    f.setInt(target, (int) number(v, path));
                } else if (t == long.class) {
                    f.setLong(target, number(v, path));
                } else if (t == boolean.class) {
                    if (!(v instanceof Boolean)) throw new IllegalArgumentException("'" + path + "' must be true or false");
                    f.setBoolean(target, (Boolean) v);
                } else if (t == List.class) {
                    Class<?> elem = (Class<?>) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
                    List<Object> out = new ArrayList<>();
                    if (v instanceof List) {
                        int i = 0;
                        for (Object o : (List<Object>) v) {
                            String itemPath = path + "." + i++;
                            if (o == null) continue;
                            if (elem == String.class) {
                                out.add(String.valueOf(o).trim());
                            } else {
                                if (!(o instanceof Map)) throw new IllegalArgumentException("'" + itemPath + "' must be a section");
                                Object item = elem.getDeclaredConstructor().newInstance();
                                bind(item, (Map<String, Object>) o, itemPath + ".");
                                out.add(item);
                            }
                        }
                    } else if (v != null) {
                        throw new IllegalArgumentException("'" + path + "' must be a list");
                    }
                    f.set(target, out);
                } else {
                    if (v == null) continue;
                    if (!(v instanceof Map)) throw new IllegalArgumentException("'" + path + "' must be a section");
                    bind(f.get(target), (Map<String, Object>) v, path + ".");
                }
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static long number(Object v, String path) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + path + "' must be a whole number, got '" + v + "'");
        }
    }

    public String get(String path) {
        Object o = resolve(path, null);
        return o == null ? "" : o.toString();
    }

    public void set(String path, String value) {
        resolve(path, value);
    }

    private Object resolve(String path, String newValue) {
        try {
            Object cur = this;
            String[] parts = path.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                cur = cur instanceof List ? ((List<?>) cur).get(Integer.parseInt(parts[i]))
                                          : cur.getClass().getField(parts[i]).get(cur);
            }
            Field f = cur.getClass().getField(parts[parts.length - 1]);
            if (newValue != null) f.set(cur, newValue);
            return f.get(cur);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException("No setting " + path, e);
        }
    }

    // ── validation ───────────────────────────────────────────────────────────

    private static final Pattern AE = Pattern.compile("[\\x20-\\x5B\\x5D-\\x7E]{1,16}");

    public List<String> errors() {
        List<String> e = new ArrayList<>();
        port(e, "listener.port", listener.port);
        ae(e, "listener.aeTitle", listener.aeTitle);
        for (String a : listener.allowedCallingAeTitles) ae(e, "listener.allowedCallingAeTitles", a);
        dicomTls(e, "listener.tls", listener.tls, true);

        URI u = null;
        try {
            u = URI.create(source.baseUrl.trim());
        } catch (IllegalArgumentException ex) {
            e.add("source.baseUrl is not a valid URL");
        }
        if (u != null && (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null)) {
            e.add("source.baseUrl must be an https:// URL of the DICOM Web Proxy, e.g. https://10.8.0.20:8484/dicom-web");
        }
        String mode = source.authMode.toUpperCase(Locale.ROOT);
        if ("OAUTH2".equals(mode)) {
            required(e, "source.clientId", source.clientId);
            required(e, "source.clientSecret", source.clientSecret);
            if (!source.tokenEndpoint.isBlank() && !source.tokenEndpoint.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                e.add("source.tokenEndpoint must be an https:// URL");
            }
        } else if ("API_KEY".equals(mode)) {
            required(e, "source.apiKey", source.apiKey);
        } else {
            e.add("source.authMode must be OAUTH2 or API_KEY");
        }
        readable(e, "source.trustCertPath", source.trustCertPath);
        readable(e, "source.clientKeystorePath", source.clientKeystorePath);
        if (source.responseTimeoutMs < 10_000) e.add("source.responseTimeoutMs must be at least 10000");

        if (destinations.isEmpty()) e.add("destinations: at least one destination is required");
        Set<String> names = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (int i = 0; i < destinations.size(); i++) {
            Destination d = destinations.get(i);
            String p = "destinations." + i;
            if (d.name.isBlank()) e.add(p + ".name is required");
            else if (!names.add(d.name)) e.add(p + ".name '" + d.name + "' is used twice");
            if (d.route.isBlank()) e.add(p + ".route is required (a route name or *)");
            else if (!routes.add(d.route)) e.add(p + ".route '" + d.route + "' is used by two destinations");
            required(e, p + ".host", d.host);
            port(e, p + ".port", d.port);
            ae(e, p + ".aeTitle", d.aeTitle);
            ae(e, p + ".callingAeTitle", d.callingAeTitle);
            dicomTls(e, p + ".tls", d.tls, false);
        }
        if (workers < 1 || workers > 16) e.add("workers must be 1..16");
        if (retry.maxAttempts < 0) e.add("retry.maxAttempts must be 0 (forever) or more");
        if (retry.firstAttemptDelaySeconds < 0) e.add("retry.firstAttemptDelaySeconds must be 0 or more");
        if (retry.initialDelaySeconds < 1 || retry.maxDelaySeconds < retry.initialDelaySeconds) {
            e.add("retry: need 1 <= initialDelaySeconds <= maxDelaySeconds");
        }
        required(e, "spool.dir", spool.dir);
        try {
            java.util.logging.Level.parse(logging.level.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            e.add("logging.level must be SEVERE, WARNING, INFO, FINE or ALL");
        }
        return e;
    }

    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        if (!listener.tls.enabled && !isLoopback(listener.bindAddress)) {
            w.add("listener.tls is off: KOS notifications arrive unencrypted (they hold pseudonymized identifiers only).");
        }
        if (listener.allowedCallingAeTitles.isEmpty()) {
            w.add("listener.allowedCallingAeTitles is empty: any AE title may send pull requests.");
        }
        for (int i = 0; i < destinations.size(); i++) {
            Destination d = destinations.get(i);
            if (!d.tls.enabled && !d.host.isBlank() && !isLoopback(d.host)) {
                w.add("destinations." + i + ".tls is off: studies go to " + d.name + " unencrypted.");
            }
        }
        return w;
    }

    private static void dicomTls(List<String> e, String p, DicomTls t, boolean server) {
        if (!t.enabled) return;
        if (server) required(e, p + ".keystorePath", t.keystorePath);
        readable(e, p + ".keystorePath", t.keystorePath);
        readable(e, p + ".trustCertPath", t.trustCertPath);
        if (server && t.requireClientCert && t.trustCertPath.isBlank()) {
            e.add(p + ".trustCertPath is required when requireClientCert is true");
        }
    }

    private static void required(List<String> e, String path, String v) {
        if (v == null || v.isBlank()) e.add(path + " is required");
    }

    private static void readable(List<String> e, String path, String v) {
        if (v != null && !v.isBlank() && !Files.isReadable(Paths.get(v.trim()))) {
            e.add(path + ": file not found or not readable: " + v.trim());
        }
    }

    private static void port(List<String> e, String path, int p) {
        if (p < 1 || p > 65535) e.add(path + " must be 1..65535");
    }

    private static void ae(List<String> e, String path, String v) {
        if (v == null || !AE.matcher(v).matches() || v.isBlank()) e.add(path + " must be 1-16 characters, no backslash");
    }

    static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.trim();
        if (h.equalsIgnoreCase("localhost")) return true;
        if (!h.matches("[0-9.:\\[\\]a-fA-F]+")) return false;
        try {
            return InetAddress.getByName(h.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }
}
