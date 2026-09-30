package nl.aifi.pull;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@code <configDir>/pull-agent.yaml}. On the first start the commented default file
 * is written. Secrets typed in plaintext are encrypted in place ({@code enc:v1:...}, comments and layout preserved), so after
 * one start the file no longer holds a readable password.
 */
public final class ConfigLoader {

    private static final Logger LOG = Logger.getLogger(ConfigLoader.class.getName());
    public static final String FILE = "pull-agent.yaml";
    public static final String KEY_FILE = "pull-agent.key";

    public static final class Loaded {
        public final Config config;
        public final Keys keys;
        public final Path file;
        public final boolean created;

        Loaded(Config config, Keys keys, Path file, boolean created) {
            this.config = config;
            this.keys = keys;
            this.file = file;
            this.created = created;
        }
    }

    private ConfigLoader() {}

    public static Loaded load(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve(FILE);
        boolean created = false;
        if (!Files.exists(file)) {
            copyResource("/default-pull-agent.yaml", file);
            created = true;
        }
        Keys keys = Keys.loadOrCreate(configDir.resolve(KEY_FILE));

        String text = Files.readString(file, StandardCharsets.UTF_8);
        Config cfg = parse(text);

        List<String> plaintext = new ArrayList<>();
        for (String p : cfg.secretPaths()) {
            String v = cfg.get(p);
            if (!v.isEmpty() && !keys.isEncrypted(v)) plaintext.add(p);
        }
        if (!plaintext.isEmpty()) {
            String updated = text;
            for (String p : plaintext) updated = encryptInText(updated, p, cfg.get(p), keys);
            if (!updated.equals(text)) {
                writeAtomically(file, updated);
                LOG.info("Encrypted plaintext secret(s) in " + file.getFileName() + ": " + plaintext);
            }
        }
        for (String p : cfg.secretPaths()) cfg.set(p, keys.decrypt(cfg.get(p)));
        resolveRelative(cfg, configDir.toAbsolutePath().getParent());
        return new Loaded(cfg, keys, file, created);
    }

    @SuppressWarnings("unchecked")
    public static Config parse(String yamlText) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Object root = new Yaml(new SafeConstructor(opts)).load(yamlText);
        if (root != null && !(root instanceof Map)) throw new IllegalArgumentException("pull-agent.yaml must be a YAML mapping");
        return Config.fromMap((Map<String, Object>) root);
    }

    /**
     * Replace the value of the setting's key line with its ciphertext. The key name is
     * the last part of the path; the line must carry exactly the plaintext value.
     */
    static String encryptInText(String text, String path, String plaintext, Keys keys) {
        String key = path.substring(path.lastIndexOf('.') + 1);
        Pattern line = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + ":[ \\t]*)(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^#\\r\\n]*?)([ \\t]+#[^\\r\\n]*)?[ \\t]*$");
        Matcher m = line.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (unquote(m.group(2)).equals(plaintext)) {
                String tail = m.group(3) == null ? "" : m.group(3);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "\"" + keys.encrypt(plaintext) + "\"" + tail));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String unquote(String raw) {
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    /** Relative file settings resolve against the install folder (parent of config/). */
    private static void resolveRelative(Config c, Path base) {
        if (base == null) return;
        for (String p : c.pathSettings()) {
            String v = c.get(p);
            if (!v.isBlank() && !Path.of(v).isAbsolute()) c.set(p, base.resolve(v).normalize().toString());
        }
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Bundled resource missing: " + resource);
            Files.copy(in, target);
        }
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
