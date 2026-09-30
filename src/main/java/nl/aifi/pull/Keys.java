package nl.aifi.pull;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The gateway's local master key ({@code config/gateway.key}, 32 random bytes,
 * generated on first start) and the purpose-bound keys derived from it:
 *
 * <ul>
 *   <li>secret encryption — config secrets are stored as {@code enc:v1:<base64>}
 *       (AES-256-GCM), so the YAML file never holds a plaintext password;</li>
 *   <li>UID derivation — pseudonymized UIDs are an HMAC of the original, so a
 *       re-sent study gets the same UIDs and the mapping cannot be recomputed
 *       without this key;</li>
 *   <li>log hashing — original PatientIDs are logged as a keyed hash.</li>
 * </ul>
 *
 * <p>Losing the key file makes stored secrets unreadable (re-enter them) and changes
 * the UIDs of studies re-sent afterwards; it does not affect studies already delivered.
 */
public final class Keys {

    public static final String ENC_PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;

    private final byte[] secretKey;
    private final byte[] uidKey;
    private final byte[] logKey;
    private final SecureRandom rnd = new SecureRandom();

    private Keys(byte[] master) {
        this.secretKey = derive(master, "secret-encryption");
        this.uidKey = derive(master, "uid-derivation");
        this.logKey = derive(master, "log-hash");
    }

    /** Load the master key from {@code keyFile}, creating it on first use. */
    public static Keys loadOrCreate(Path keyFile) throws IOException {
        if (!Files.exists(keyFile)) {
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            byte[] master = new byte[32];
            new SecureRandom().nextBytes(master);
            Files.write(keyFile, Base64.getEncoder().encode(master));
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignore) {
                // Windows: install-service.ps1 restricts the config folder ACL.
            }
        }
        byte[] master = Base64.getDecoder().decode(
                new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII).trim());
        if (master.length < 32) throw new IOException("Key file " + keyFile + " is corrupt (too short)");
        return new Keys(master);
    }

    /** For tests: keys from a fixed master. */
    public static Keys fromMaster(byte[] master) {
        return new Keys(master);
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || isEncrypted(plaintext)) return plaintext;
        try {
            byte[] iv = new byte[IV_LEN];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secretKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    /** Decrypt an {@code enc:v1:} value; plaintext values pass through unchanged. */
    public String decrypt(String value) {
        if (!isEncrypted(value)) return value;
        try {
            byte[] in = Base64.getDecoder().decode(value.substring(ENC_PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretKey, "AES"),
                    new GCMParameterSpec(128, in, 0, IV_LEN));
            return new String(c.doFinal(in, IV_LEN, in.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt a configuration secret - was "
                    + "config/gateway.key replaced? Re-enter the secret.", e);
        }
    }

    /** HMAC-SHA256 under the UID-derivation key. */
    public byte[] uidMac(String message) {
        return hmac(uidKey, message.getBytes(StandardCharsets.UTF_8));
    }

    /** Short keyed hash for logging an identifier without revealing it. */
    public String logHash(String value) {
        if (value == null) return "-";
        byte[] h = hmac(logKey, value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("#");
        for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
        return sb.toString();
    }

    private static byte[] derive(byte[] master, String purpose) {
        return hmac(master, purpose.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
