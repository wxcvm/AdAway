package org.adaway.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** A manual, ordinary GET / observation, not a listener receipt or a browser renderer.
 * Only literal, already-owned local targets are admitted. HTTP headers AND sampled body
 * share one 4096-byte read allowance. No redirects, proxies, DNS, or trust-store changes. */
public final class ListenerResponseCheck {
    public static final int RESPONSE_LIMIT = 4096;
    private static final int SAMPLE_LIMIT = 512;
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1,
            task -> { Thread thread = new Thread(task, "listener-response-timeout"); thread.setDaemon(true); return thread; });
    static { WATCHDOG.setRemoveOnCancelPolicy(true); }

    public enum State { RESPONSE, INCOMPLETE, INVALID_RESPONSE, UNAVAILABLE, CERTIFICATE_REJECTED, SKIPPED }

    public static final class Observation {
        public final String address, url, startedUtc, contentType, sample, sampleKind, protocol, detail;
        public final int family, port, status, observedBytes, bodyBytes;
        public final boolean https, local, headersComplete, bodyComplete, sampleTruncated, readLimitReached;
        /** -1 means absent or invalid; never inferred from the content sample. */
        public final long declaredLength;
        public final State state;
        private Observation(String address, int port, boolean https, String startedUtc, boolean local, State state,
                int status, String contentType, long declaredLength, boolean headersComplete, boolean bodyComplete,
                byte[] body, int observedBytes, boolean readLimitReached, String protocol, String detail) {
            this.address = address; this.port = port; this.https = https; this.startedUtc = startedUtc;
            this.url = browserUrl(address, port, https); this.family = address.contains(":") ? 6 : 4;
            this.local = local; this.state = state; this.status = status; this.contentType = contentType;
            this.declaredLength = declaredLength; this.headersComplete = headersComplete; this.bodyComplete = bodyComplete;
            this.observedBytes = observedBytes; this.bodyBytes = body.length; this.readLimitReached = readLimitReached;
            this.sampleTruncated = !bodyComplete || body.length > SAMPLE_LIMIT;
            this.protocol = protocol; this.detail = detail;
            byte[] prefix = java.util.Arrays.copyOf(body, Math.min(body.length, SAMPLE_LIMIT));
            String image = imagePrefix(prefix);
            String text = image.isEmpty() ? printable(prefix) : null;
            this.sampleKind = !image.isEmpty() ? image + " prefix only" : text != null ? "text prefix" : "hex prefix";
            this.sample = text != null ? text : hex(prefix);
        }

        public String describe() {
            StringBuilder text = new StringBuilder(url).append(" [IPv").append(family).append("]\n")
                    .append("UTC: ").append(startedUtc).append(" · ").append(state).append('\n');
            if (status > 0) text.append("HTTP: ").append(status).append(" · Content-Type: ")
                    .append(contentType.isEmpty() ? "unknown" : contentType).append('\n');
            text.append("Content-Length: ").append(declaredLength < 0 ? "unknown" : Long.toString(declaredLength))
                    .append(" · observed bytes (headers + body): ").append(observedBytes).append(" / ").append(RESPONSE_LIMIT)
                    .append("\nheaders complete: ").append(headersComplete).append(" · body complete: ").append(bodyComplete)
                    .append(" · sample truncated/incomplete: ").append(sampleTruncated).append('\n');
            if (!protocol.isEmpty()) text.append("TLS: ").append(protocol).append(" (chain and literal-IP identity verified)\n");
            if (!detail.isEmpty()) text.append(detail).append('\n');
            if (bodyBytes > 0) text.append(sampleKind).append(": ").append(sample).append('\n');
            return text.toString();
        }
    }

    public static final class Report {
        public final String startedUtc, configurationFingerprint, savedTarget;
        public final List<Observation> observations;
        private Report(String startedUtc, String savedTarget, List<Observation> observations) {
            this.startedUtc = startedUtc; this.savedTarget = savedTarget;
            this.configurationFingerprint = fingerprint(savedTarget);
            this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
        }
        public String describe() {
            StringBuilder text = new StringBuilder("UTC: ").append(startedUtc)
                    .append("\nConfiguration SHA-256: ").append(configurationFingerprint).append('\n')
                    .append(savedTarget).append("\nGET / · ordinary Web requests; may appear in request logs/statistics.\n")
                    .append("A prefix is not a complete image or proof of browser rendering. No listener receipt is inferred.\n");
            for (Observation observation : observations) text.append('\n').append(observation.describe());
            return text.toString();
        }
    }

    private ListenerResponseCheck() { }

    /** At most two enabled saved families, and HTTP/HTTPS once each. Wildcards use same-family loopback. */
    public static Report check(ListenerSettings settings, ListenerArrivalCheck.LocalAddresses local,
            ListenerDiagnosticSession session, BooleanSupplier currentConfiguration) throws IOException {
        session.requireCurrentConfiguration(currentConfiguration);
        session.begin();
        try {
            String started = Instant.now().toString();
            List<Observation> result = new ArrayList<>();
            for (String address : settings.diagnosticAddresses()) {
                session.progress("HTTP / · " + address + ":" + settings.http);
                result.add(checkEndpoint(address, settings.http, false, local, session));
                session.progress("HTTPS / · " + address + ":" + settings.https);
                result.add(checkEndpoint(address, settings.https, true, local, session));
            }
            session.checkCancelled();
            return new Report(started, settings.arguments(), result);
        } finally { session.finish(); }
    }

    public static Observation checkEndpoint(String address, int port, boolean https,
            ListenerArrivalCheck.LocalAddresses local, ListenerDiagnosticSession session) throws IOException {
        return checkEndpoint(address, port, https, local, session, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    // Test-only injected anchors are in memory. Production always uses the platform default factory.
    static Observation checkEndpoint(String address, int port, boolean https,
            ListenerArrivalCheck.LocalAddresses local, ListenerDiagnosticSession session, SSLSocketFactory factory) throws IOException {
        session.checkCancelled();
        String started = Instant.now().toString();
        if (!ListenerSettings.isLiteral(address) || port < 1 || port > 65535)
            throw new IllegalArgumentException("A literal IP and valid port are required");
        InetAddress target = InetAddress.getByName(address);
        if (address.contains(":") && target.getAddress().length != 16) {
            return empty(address, port, https, started, false, State.SKIPPED,
                    "This IPv4-mapped literal resolves as IPv4 on this platform; no IPv6 request was attempted.");
        }
        try {
            if (!local.contains(address)) return empty(address, port, https, started, false, State.SKIPPED,
                    "Address is not currently owned by this device; no connection or address creation attempted.");
            session.checkCancelled();
        } catch (IOException failure) {
            session.checkCancelled();
            return empty(address, port, https, started, false, State.SKIPPED, "Local ownership could not be verified; no connection attempted.");
        }
        try (Socket transport = new Socket()) {
            ListenerDiagnosticSession.Registration registration = session.register(transport);
            ScheduledFuture<?> timeout = WATCHDOG.schedule(() -> close(transport), https ? 2500 : 2000, TimeUnit.MILLISECONDS);
            try {
                transport.connect(new InetSocketAddress(target, port), 800);
                transport.setSoTimeout(2000);
                if (!https) return exchange(transport, address, port, false, started, "", session);
                // The actual IP URL is the peer identity. Never substitute localhost; IP literals have no SNI.
                try (SSLSocket tls = (SSLSocket) factory.createSocket(transport, address, port, true)) {
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(Collections.emptyList());
                    tls.setSSLParameters(parameters); tls.setSoTimeout(2000);
                    session.checkCancelled(); tls.startHandshake(); session.checkCancelled();
                    return exchange(tls, address, port, true, started, tls.getSession().getProtocol(), session);
                }
            } finally { timeout.cancel(false); registration.close(); }
        } catch (SSLException failure) {
            session.checkCancelled();
            return empty(address, port, https, started, true,
                    certificateRejected(failure) ? State.CERTIFICATE_REJECTED : State.UNAVAILABLE,
                    certificateRejected(failure) ? "Certificate chain or literal-IP identity rejected; no bypass or retry with localhost."
                            : "TLS did not complete; this does not identify certificate pinning or QUIC behavior.");
        } catch (IOException failure) {
            session.checkCancelled();
            return empty(address, port, https, started, true, State.UNAVAILABLE, "Connection or response timed out/failed; no complete HTTP response observed.");
        }
    }

    public static String browserUrl(String address, int port, boolean https) {
        if (!ListenerSettings.isLiteral(address) || port < 1 || port > 65535)
            throw new IllegalArgumentException("A literal IP and valid port are required");
        return (https ? "https://" : "http://") + (address.contains(":") ? "[" + address + "]" : address) + ":" + port + "/";
    }

    private static Observation exchange(Socket socket, String address, int port, boolean https, String started,
            String protocol, ListenerDiagnosticSession session) throws IOException {
        String host = (address.contains(":") ? "[" + address + "]" : address) + ":" + port;
        socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\n"
                + "Accept: text/html,image/webp,image/png,image/*;q=0.8,*/*;q=0.5\r\n"
                + "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(RESPONSE_LIMIT);
        InputStream input = socket.getInputStream();
        Header header = null;
        int headerBytes = -1;
        boolean eof = false, failed = false;
        // Read headers bytewise so the body allowance includes every header byte and no extra byte is consumed.
        try {
            int tail = 0;
            while (raw.size() < RESPONSE_LIMIT && headerBytes < 0) {
                session.checkCancelled();
                int next = input.read();
                if (next < 0) { eof = true; break; }
                raw.write(next); tail = (tail << 8) | next;
                if (tail == 0x0d0a0d0a) { headerBytes = raw.size(); header = parseHeader(raw.toByteArray()); }
            }
            if (header != null && header.valid) {
                long expected = header.noBody ? 0 : header.length;
                byte[] buffer = new byte[1024];
                while (raw.size() < RESPONSE_LIMIT && (expected < 0 || raw.size() - headerBytes < expected)) {
                    session.checkCancelled();
                    int allowance = Math.min(buffer.length, RESPONSE_LIMIT - raw.size());
                    if (expected >= 0) allowance = (int)Math.min(allowance, expected - (raw.size() - headerBytes));
                    int count = input.read(buffer, 0, allowance);
                    if (count < 0) { eof = true; break; }
                    if (count > 0) raw.write(buffer, 0, count);
                }
            }
        } catch (IOException failure) { session.checkCancelled(); failed = true; }
        session.checkCancelled();
        boolean limit = raw.size() == RESPONSE_LIMIT;
        byte[] body = headerBytes < 0 ? new byte[0] : java.util.Arrays.copyOfRange(raw.toByteArray(), headerBytes, raw.size());
        if (header == null) return new Observation(address, port, https, started, true,
                raw.size() == 0 ? State.UNAVAILABLE : State.INCOMPLETE, 0, "", -1, false, false, body, raw.size(), limit, protocol,
                limit ? "Headers exceeded the shared 4096-byte allowance; no body sample." : "HTTP headers incomplete; connection ended or timed out.");
        boolean complete = header.valid && !failed && (header.noBody || (!header.transferEncoded
                && (header.length >= 0 ? body.length == header.length : eof)));
        String detail = !header.valid ? "Malformed or unsupported HTTP headers; status is observational only."
                : header.transferEncoded ? "Transfer-encoded body prefix is not decoded; response completeness and image identity are unknown."
                : complete ? "HTTP response observed; this is not proof that a browser rendered it."
                : limit ? "Shared 4096-byte allowance reached; response/body is incomplete."
                : "Body incomplete: declared length not received, connection failed, or timeout.";
        return new Observation(address, port, https, started, true,
                !header.valid ? State.INVALID_RESPONSE : complete ? State.RESPONSE : State.INCOMPLETE,
                header.status, header.contentType, header.length, true, complete, body, raw.size(), limit, protocol, detail);
    }

    private static final class Header {
        int status; long length = -1; String contentType = ""; boolean valid = true, transferEncoded, noBody;
    }
    private static Header parseHeader(byte[] bytes) {
        Header result = new Header();
        String[] lines = new String(bytes, StandardCharsets.ISO_8859_1).split("\r\n");
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: [^\\r\\n]*)?")) { result.valid = false; return result; }
        result.status = Integer.parseInt(lines[0].substring(9, 12));
        if (result.status < 200) result.valid = false; // No interim response is misreported as the final representation.
        result.noBody = result.status == 204 || result.status == 304;
        boolean lengthSeen = false, typeSeen = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) { result.valid = false; continue; }
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            if (value.chars().anyMatch(c -> c < 32 && c != 9 || c == 127)) result.valid = false;
            if (key.equals("content-length")) {
                long length = -1;
                try { if (value.matches("[0-9]+")) length = Long.parseLong(value); } catch (NumberFormatException ignored) { }
                if (length < 0 || lengthSeen && length != result.length) result.valid = false;
                result.length = length; lengthSeen = true;
            } else if (key.equals("content-type")) {
                if (typeSeen && !result.contentType.equals(value)) result.valid = false;
                result.contentType = value; typeSeen = true;
            } else if (key.equals("transfer-encoding")) result.transferEncoded = true;
        }
        if (result.transferEncoded && lengthSeen) result.valid = false;
        return result;
    }

    private static Observation empty(String address, int port, boolean https, String started, boolean local, State state, String detail) {
        return new Observation(address, port, https, started, local, state, 0, "", -1, false, false,
                new byte[0], 0, false, "", detail);
    }
    private static String printable(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t') ? null : text;
        } catch (CharacterCodingException ignored) { return null; }
    }
    private static String imagePrefix(byte[] bytes) {
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "WebP";
        if (bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "PNG";
        if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "JPEG";
        return "";
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
    private static String fingerprint(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean certificateRejected(SSLException failure) {
        if (failure instanceof SSLPeerUnverifiedException) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof CertificateException) return true;
        return false;
    }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
}
