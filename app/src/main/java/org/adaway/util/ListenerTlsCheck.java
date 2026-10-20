package org.adaway.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Strict TLS on an already selected local address. Never installs anchors,
 * disables certificate validation, follows redirects, or resolves an origin. */
public final class ListenerTlsCheck {
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1,
            task -> { Thread thread = new Thread(task, "listener-tls-timeout"); thread.setDaemon(true); return thread; });
    static { WATCHDOG.setRemoveOnCancelPolicy(true); }
    public enum State { UNAVAILABLE, RECEIPT, NO_RECEIPT, CERTIFICATE_REJECTED }
    public static final class Result {
        public final State state;
        public final String protocol;
        private Result(State state, String protocol) { this.state = state; this.protocol = protocol; }
        public String describe() {
            return switch (state) {
                case RECEIPT -> protocol + " 握手、证书链/名称校验与 HTTPS 加密回执通过";
                case NO_RECEIPT -> protocol + " 与证书校验通过，但未收到 AdAway 加密回执";
                case CERTIFICATE_REJECTED -> "证书链或名称校验拒绝；未跳过验证，TLS 未完成";
                case UNAVAILABLE -> "连接或 TLS 未完成，不能确认 HTTPS 到达";
            };
        }
    }
    private ListenerTlsCheck() { }

    public static Result check(String address, int port, String host, ListenerArrivalCheck.LocalAddresses local) {
        return check(address, port, host, local, (SSLSocketFactory)SSLSocketFactory.getDefault());
    }

    // A fixture can supply its own in-memory trusted anchor. The UI always uses
    // the platform default factory; this does not change the device trust store.
    static Result check(String address, int port, String host, ListenerArrivalCheck.LocalAddresses local, SSLSocketFactory factory) {
        if (!ListenerSettings.isLiteral(address) || port < 1 || port > 65535) return new Result(State.UNAVAILABLE, "");
        boolean literal = ListenerSettings.isLiteral(host);
        final String identity;
        try { identity = literal || "localhost".equals(host) ? host : ListenerArrivalCheck.normalizeDomain(host); }
        catch (IllegalArgumentException exception) { return new Result(State.UNAVAILABLE, ""); }
        try {
            if (!local.contains(address)) return new Result(State.UNAVAILABLE, "");
            return connect(address, port, identity, literal, factory);
        } catch (SSLException exception) {
            return new Result(certificateRejected(exception) ? State.CERTIFICATE_REJECTED : State.UNAVAILABLE, "");
        } catch (IOException exception) { return new Result(State.UNAVAILABLE, ""); }
    }

    private static boolean certificateRejected(SSLException exception) {
        if (exception instanceof SSLPeerUnverifiedException) return true;
        for (Throwable cause = exception; cause != null; cause = cause.getCause())
            if (cause instanceof CertificateException) return true;
        return false;
    }

    private static Result connect(String address, int port, String identity, boolean literal, SSLSocketFactory factory) throws IOException {
        try (Socket transport = new Socket()) {
            ScheduledFuture<?> timeout = WATCHDOG.schedule(() -> {
                try { transport.close(); } catch (IOException ignored) { /* Close cancels this diagnostic only. */ }
            }, 2500, TimeUnit.MILLISECONDS);
            try {
                transport.connect(new InetSocketAddress(InetAddress.getByName(address), port), 800);
                transport.setSoTimeout(2000);
                try (SSLSocket tls = (SSLSocket)factory.createSocket(transport, identity, port, true)) {
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!literal) parameters.setServerNames(Collections.singletonList(new SNIHostName(identity)));
                    tls.setSSLParameters(parameters);
                    tls.setSoTimeout(2000);
                    tls.startHandshake();
                    String protocol = tls.getSession().getProtocol();
                    boolean received;
                    try { received = ListenerArrivalCheck.exchange(tls, identity); }
                    catch (IOException exception) { received = false; }
                    return new Result(received ? State.RECEIPT : State.NO_RECEIPT, protocol);
                }
            } finally { timeout.cancel(false); }
        }
    }
}
