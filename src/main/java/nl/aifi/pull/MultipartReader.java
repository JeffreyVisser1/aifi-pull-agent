package nl.aifi.pull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Streaming reader for a {@code multipart/related} body (RFC 2046), as returned by WADO-RS.
 * Each part body is copied to a caller-supplied stream while the delimiter is searched with
 * KMP, so a part of any size never has to fit in memory.
 */
final class MultipartReader {

    private final InputStream in;
    private final byte[] delimiter;
    private final int[] fail;
    private boolean started;
    private boolean finished;

    MultipartReader(InputStream body, String boundary) {
        // A virtual CRLF in front lets the first delimiter be found like every other one.
        this.in = new SequenceInputStream(new ByteArrayInputStream(new byte[] {'\r', '\n'}),
                new java.io.BufferedInputStream(body, 1 << 16));
        this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        this.fail = failure(delimiter);
    }

    /** The boundary parameter of a multipart Content-Type header, or null. */
    static String boundary(String contentType) {
        if (contentType == null) return null;
        for (String param : contentType.split(";")) {
            String p = param.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) b = b.substring(1, b.length() - 1);
                return b.isEmpty() ? null : b;
            }
        }
        return null;
    }

    /**
     * Copy the next part's body to {@code out}.
     *
     * @return the part headers (lower-case names), or null after the last part
     */
    Map<String, String> next(OutputStream out) throws IOException {
        if (finished) return null;
        if (!started) {
            copyUntilDelimiter(OutputStream.nullOutputStream());   // preamble
            started = true;
        }
        int a = in.read();
        int b = in.read();
        if (a == '-' && b == '-') {
            finished = true;
            return null;
        }
        // transport padding, then CRLF
        while (a == ' ' || a == '\t') { a = b; b = in.read(); }
        if (a != '\r' || b != '\n') throw new IOException("malformed multipart body: no CRLF after the boundary");
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine();
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        copyUntilDelimiter(out);
        return headers;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int n = in.read();
                if (n == '\n') return line.toString(StandardCharsets.ISO_8859_1);
                line.write(c);
                if (n == -1) break;
                line.write(n);
            } else {
                line.write(c);
            }
            if (line.size() > 8192) throw new IOException("malformed multipart body: header line too long");
        }
        throw new EOFException("multipart body ended inside the part headers");
    }

    /** Copy bytes to {@code out} until the delimiter; the delimiter itself is consumed. */
    private void copyUntilDelimiter(OutputStream out) throws IOException {
        int j = 0;
        int b;
        while ((b = in.read()) != -1) {
            while (j > 0 && b != (delimiter[j] & 0xff)) {
                int k = fail[j - 1];
                out.write(delimiter, 0, j - k);                   // can no longer be part of a match
                j = k;
            }
            if (b == (delimiter[j] & 0xff)) {
                if (++j == delimiter.length) return;
            } else {
                out.write(b);
            }
        }
        throw new EOFException("multipart body ended without its closing boundary");
    }

    private static int[] failure(byte[] p) {
        int[] f = new int[p.length];
        for (int i = 1, k = 0; i < p.length; i++) {
            while (k > 0 && p[i] != p[k]) k = f[k - 1];
            if (p[i] == p[k]) k++;
            f[i] = k;
        }
        return f;
    }
}
