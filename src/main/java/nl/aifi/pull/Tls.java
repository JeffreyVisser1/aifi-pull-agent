package nl.aifi.pull;

import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * One place for every TLS decision, so all connections share the same policy:
 * TLS 1.3 / 1.2 only, and for TLS 1.2 only forward-secret AEAD cipher suites
 * (the BCP 195 recommendation that DICOM PS3.15 "BCP 195 TLS Profile" refers to).
 */
public final class Tls {

    public static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    public static final String[] CIPHER_SUITES = {
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    };

    private Tls() {}

    /** {@link #CIPHER_SUITES} minus the ones this Java runtime lacks (ChaCha20 needs Java 12+). */
    public static String[] cipherSuites() {
        try {
            java.util.Set<String> supported = new java.util.HashSet<>(java.util.Arrays.asList(
                    SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()));
            return java.util.Arrays.stream(CIPHER_SUITES).filter(supported::contains).toArray(String[]::new);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No TLS support in this Java runtime", e);
        }
    }

    /** Trust exactly the certificates in a PEM file; {@code null} path = the Java trust store. */
    public static TrustManager[] trustManagers(String pemPath) throws IOException, GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (pemPath == null || pemPath.isBlank()) {
            tmf.init((KeyStore) null);
            return tmf.getTrustManagers();
        }
        Path pem = Paths.get(pemPath.trim());
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream in = Files.newInputStream(pem)) {
            certs = cf.generateCertificates(in);
        }
        if (certs.isEmpty()) throw new IOException("No certificate found in " + pem);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        int n = 0;
        for (Certificate c : certs) ks.setCertificateEntry("trusted-" + n++, c);
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    /** Own certificate + private key from a PKCS#12 file; {@code null} path = none. */
    public static KeyManager[] keyManagers(String p12Path, String password) throws IOException, GeneralSecurityException {
        if (p12Path == null || p12Path.isBlank()) return null;
        char[] pwd = password == null ? new char[0] : password.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(p12Path.trim()))) {
            ks.load(in, pwd);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pwd);
        return kmf.getKeyManagers();
    }

    public static SSLContext context(KeyManager[] km, TrustManager[] tm) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(km, tm, new SecureRandom());
        return ctx;
    }

    /**
     * Put a dcm4che connection into TLS mode per the configuration. The certificate and
     * trust anchors are set on the (single-purpose) device that owns the connection.
     *
     * @param server true for the listener, false for an outgoing association
     */
    public static void apply(Device device, Connection conn, Config.DicomTls t, boolean server)
            throws IOException, GeneralSecurityException {
        if (!t.enabled) return;
        conn.setTlsProtocols(PROTOCOLS);
        conn.setTlsCipherSuites(cipherSuites());
        KeyManager[] km = keyManagers(t.keystorePath, t.keystorePassword);
        if (km != null) device.setKeyManager(km[0]);
        device.setTrustManager(trustManagers(t.trustCertPath)[0]);
        if (server) {
            conn.setTlsNeedClientAuth(t.requireClientCert);
        } else {
            conn.setTlsNeedClientAuth(false);
            if (t.verifyHostname) conn.setTlsEndpointIdentificationAlgorithm(Connection.EndpointIdentificationAlgorithm.HTTPS);
        }
    }
}
