package nl.aifi.pull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Test certificates made with the JDK's keytool: a trusted "node" identity (used by the
 * fake proxy, the gateway listener and the test sender) and an untrusted "rogue" one.
 * Both name localhost and 127.0.0.1 so host name verification is exercised for real.
 */
public final class TestPki {

    public static final String PASSWORD = "test-pass";
    private static Path dir;

    private TestPki() {}

    public static synchronized Path dir() throws IOException, InterruptedException {
        if (dir == null) {
            Path d = Files.createTempDirectory("aifigw-pki");
            make(d, "node");
            make(d, "rogue");
            dir = d;
        }
        return dir;
    }

    public static String p12(String name) throws Exception { return dir().resolve(name + ".p12").toString(); }
    public static String pem(String name) throws Exception { return dir().resolve(name + ".pem").toString(); }

    private static void make(Path d, String name) throws IOException, InterruptedException {
        String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
        run(List.of(keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=localhost, O=" + name, "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "30", "-storetype", "PKCS12", "-keystore", d.resolve(name + ".p12").toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD));
        run(List.of(keytool, "-exportcert", "-rfc", "-alias", name, "-keystore", d.resolve(name + ".p12").toString(),
                "-storepass", PASSWORD, "-file", d.resolve(name + ".pem").toString()));
    }

    private static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException("keytool failed: " + out);
    }
}
