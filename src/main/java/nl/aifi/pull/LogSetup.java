package nl.aifi.pull;


import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Rotating file log ({@code logs/gateway.N.log}) plus console, one line per event. */
public final class LogSetup {

    private LogSetup() {}

    public static void console() {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        Handler h = new ConsoleHandler();
        h.setFormatter(new LineFormatter());
        h.setLevel(Level.ALL);
        root.addHandler(h);
        root.setLevel(Level.INFO);
        quiet();
    }

    public static void configure(Config.Logging c) throws IOException {
        Path dir = Path.of(c.dir);
        Files.createDirectories(dir);
        Level level = Level.parse(c.level.toUpperCase(Locale.ROOT));
        Logger root = Logger.getLogger("");
        FileHandler fh = new FileHandler(dir.resolve("pull-agent.%g.log").toString(),
                Math.max(1, c.maxFileMb) * 1024 * 1024, Math.max(1, c.maxFiles), true);
        fh.setEncoding("UTF-8");
        fh.setFormatter(new LineFormatter());
        fh.setLevel(Level.ALL);
        root.addHandler(fh);
        root.setLevel(level);
        quiet();
    }

    /** dcm4che logs every PDU at INFO; keep its association-level noise out of the log. */
    private static void quiet() {
        Logger.getLogger("org.dcm4che3").setLevel(Level.WARNING);
        Logger.getLogger("org.mariadb").setLevel(Level.WARNING);
    }

    static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160);
            String logger = r.getLoggerName() == null ? "" : r.getLoggerName();
            sb.append(TS.format(Instant.ofEpochMilli(r.getMillis()))).append(' ')
              .append(String.format("%-7s", r.getLevel().getName())).append(' ')
              .append(logger.substring(logger.lastIndexOf('.') + 1)).append(": ")
              .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
