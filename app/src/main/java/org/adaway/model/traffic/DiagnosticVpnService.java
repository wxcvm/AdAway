package org.adaway.model.traffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.adaway.R;
import org.adaway.AdAwayApplication;
import org.adaway.model.root.HijackModel;
import org.adaway.ui.compose.MainActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * Explicitly opt-in diagnostic TUN, *not* a full-traffic VPN.
 *
 * Only routes 198.18.0.1/32 to this service. It echoes valid IPv4 UDP
 * packets to port 53535 and relays small packets addressed to port 53536
 * through a protected socket to a device-local UDP responder. UDP 53537
 * allows ONE fixed example.com A or AAAA DNS question to the system-provided
 * underlying network DNS resolver, only on an explicit user test.
 * No general DNS proxy or Internet forwarding is enabled.
 * All normal traffic retains its original system route.
 *
 * Do not add a default route, DNS server, automatic restart or on-boot startup.
 * A real forwarding backend must be introduced and tested first.
 */
public final class DiagnosticVpnService extends VpnService {
    public static final String ACTION_START = "org.adaway.action.DIAGNOSTIC_TUN_START";
    public static final String ACTION_STOP = "org.adaway.action.DIAGNOSTIC_TUN_STOP";
    public static final String CHANNEL_ID = "DiagnosticTunChannel";

    private static final int NOTIFICATION_ID = 4784;
    private final AtomicLong packetCount = new AtomicLong();
    private final AtomicLong echoCount = new AtomicLong();
    private volatile boolean stopped = false;
    private ParcelFileDescriptor tun;
    private Thread reader;
    private FileOutputStream writerOutput;
    private ParcelFileDescriptor writerFd;
    private DiagnosticPacketPump pump;
    private final DiagnosticSockets sockets = new DiagnosticSockets();
    private final DiagnosticDnsAuthorization dnsPermits = new DiagnosticDnsAuthorization(
            android.os.Process.myUid(), SystemClock::elapsedRealtime);
    private static final AtomicReference<DiagnosticVpnService> RUNNING = new AtomicReference<>();
    private DiagnosticLoopbackUdpServer udpLoopback;
    private volatile long lastRelayAtMs = -200L;
    private final AtomicLong relayCount = new AtomicLong();
    private final AtomicLong dnsDiagnosticCount = new AtomicLong();

    public static boolean isActive() {
        DiagnosticVpnService service = RUNNING.get();
        return service != null && !service.stopped;
    }

    public static long getPacketCount() {
        DiagnosticVpnService service = RUNNING.get();
        return service == null ? 0 : service.packetCount.get();
    }

    public static long getEchoCount() {
        DiagnosticVpnService service = RUNNING.get();
        return service == null ? 0 : service.echoCount.get();
    }

    public static long getRelayCount() {
        DiagnosticVpnService service = RUNNING.get();
        return service == null ? 0 : service.relayCount.get();
    }

    public static long getDnsDiagnosticCount() {
        DiagnosticVpnService service = RUNNING.get();
        return service == null ? 0 : service.dnsDiagnosticCount.get();
    }

    /** In-process UI capability; no exported Intent can issue a DNS permit. */
    public static DiagnosticDnsAuthorization.Lease authorizeDnsProbe(int sourcePort, byte[] query) {
        DiagnosticVpnService service = RUNNING.get();
        return service == null || service.stopped ? null
                : service.dnsPermits.issue(android.os.Process.myUid(), sourcePort, query);
    }

    /** Must only be called from UI after successful VpnService.prepare() consent. */
    public static void start(Context context) {
        Intent intent = new Intent(context, DiagnosticVpnService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, DiagnosticVpnService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (stopped) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (isActive()) return START_NOT_STICKY;
        try {
            notifyForeground();
            // Revocation, Root transparent redirect and an active foreign VPN
            // are separate conflict checks. Never request/replace VPN consent here.
            if (isAlwaysOn() || isLockdownEnabled() || VpnService.prepare(this) != null
                    || AndroidVpnObserver.read(this, false)
                            != TrafficModePolicy.VpnOwner.NONE
                    || HijackModel.isEnabled()) {
                Timber.w("Diagnostic TUN refused: VPN not authorized or root hijack active");
                shutdown();
                return START_NOT_STICKY;
            }
            // The only route intentionally intercepted is a benchmark-only IP.
            Builder builder = new Builder()
                    .setSession("ADBlock: diagnostic TUN only")
                    .setBlocking(false) // cancellation never waits on a kernel read/write
                    .allowFamily(OsConstants.AF_INET6) // IPv6 bypasses this IPv4-only diagnostic
                    .setMtu(DiagnosticTunPlan.MTU)
                    .addAddress(DiagnosticTunPlan.LOCAL_ADDRESS,
                            DiagnosticTunPlan.LOCAL_PREFIX);
            if (!DiagnosticTunPlan.isSafeRoute(DiagnosticTunPlan.DIAGNOSTIC_TARGET,
                    DiagnosticTunPlan.TARGET_PREFIX)) {
                throw new IllegalStateException("Unsafe diagnostic route");
            }
            builder.addRoute(DiagnosticTunPlan.DIAGNOSTIC_TARGET,
                    DiagnosticTunPlan.TARGET_PREFIX);
            // TUN packets have no trustworthy sender UID. Enforce it at routing instead.
            builder.addAllowedApplication(getPackageName());
            tun = builder.establish();
            if (tun == null) {
                Timber.w("Diagnostic VPN establishment failed or consent revoked");
                shutdown();
                return START_NOT_STICKY;
            }
            stopped = false;
            udpLoopback = new DiagnosticLoopbackUdpServer();
            writerFd = tun.dup();
            writerOutput = new FileOutputStream(writerFd.getFileDescriptor());
            pump = new DiagnosticPacketPump(this::processSlowPacket, this::writeResponse,
                    sockets::close, this::shutdown);
            if (!RUNNING.compareAndSet(null, this)) throw new IOException("Diagnostic already active");
            ParcelFileDescriptor fd = tun;
            reader = new Thread(() -> readDiagnosticPackets(fd), "diagnostic-tun-reader");
            reader.setDaemon(true);
            reader.start();
            Timber.i("Diagnostic TUN up: only %s/%d routed (no forwarding)",
                    DiagnosticTunPlan.DIAGNOSTIC_TARGET, DiagnosticTunPlan.TARGET_PREFIX);
        } catch (IOException | RuntimeException | android.content.pm.PackageManager.NameNotFoundException exception) {
            Timber.w(exception, "Cannot start diagnostic TUN");
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void notifyForeground() {
        createChannel();
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void writeResponse(byte[] response) throws IOException {
        if (stopped) return;
        writerOutput.write(response);
        int header = (response[0] & 15) * 4;
        int port = ((response[header] & 255) << 8) | (response[header + 1] & 255);
        if (port == DiagnosticUdpEcho.PORT) echoCount.incrementAndGet();
        else if (port == DiagnosticUdpRelay.PORT) relayCount.incrementAndGet();
        else if (port == DiagnosticDnsCodec.PORT) dnsDiagnosticCount.incrementAndGet();
    }

    private void readDiagnosticPackets(ParcelFileDescriptor fd) {
        try (FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            byte[] packet = new byte[2048];
            StructPollfd poll = new StructPollfd();
            poll.fd = fd.getFileDescriptor();
            poll.events = (short) OsConstants.POLLIN;
            StructPollfd[] polling = {poll};
            while (!stopped && readNext(input, polling, packet)) { /* packet I/O only */ }
        } catch (IOException | ErrnoException | RuntimeException exception) {
            if (!stopped) Timber.w(exception, "Diagnostic TUN read failed");
        } finally {
            if (!stopped) shutdown();
        }
    }

    private boolean readNext(FileInputStream input, StructPollfd[] polling, byte[] packet)
            throws IOException, ErrnoException {
        if (Os.poll(polling, 100) == 0) return true;
        if (stopped) return false;
        int length = input.read(packet);
        if (length < 0) return false;
        if (length > 0) dispatch(packet, length);
        return true;
    }

    private void dispatch(byte[] packet, int length) {
        packetCount.incrementAndGet();
        byte[] response = DiagnosticUdpEcho.reply(packet, length);
        if (response != null) { pump.respond(response); return; }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRelayAtMs < 200) return;
        lastRelayAtMs = now;
        pump.submit(packet, length);
    }

    private byte[] processSlowPacket(byte[] packet) {
        byte[] response = DiagnosticUdpRelay.process(packet, packet.length, this::relayLocally);
        if (response != null || stopped) return response;
        return DiagnosticDnsRelay.process(packet, packet.length, dnsPermits,
                android.os.Process.myUid(), question -> {
                    // The only permitted question is example.com A, issued by
                    // this app's explicit probe. Match its real, immutable
                    // production rule generation before network I/O.
                    RuleSnapshotRepository.Snapshot snapshot =
                            ((AdAwayApplication) getApplication())
                                    .getSourceModel().getRuleSnapshot();
                    return DiagnosticDnsRelay.resolveFromSnapshotOrUpstream(
                            question, snapshot,
                            original -> DiagnosticDnsNetwork.exchange(this, original, sockets));
                });
    }

    private byte[] relayLocally(byte[] payload) throws IOException {
        DiagnosticLoopbackUdpServer server = udpLoopback;
        if (server == null || stopped) throw new IOException("Relay not running");
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket socket = new DatagramSocket()) {
            sockets.register(socket);
            try {
            if (!protect(socket)) throw new IOException("Socket protection refused");
            socket.setSoTimeout(900);
            socket.send(new DatagramPacket(payload, payload.length, addr, server.port()));
            byte[] received = new byte[DiagnosticUdpRelay.MAX_PAYLOAD + 1];
            DatagramPacket reply = new DatagramPacket(received, received.length);
            socket.receive(reply);
            if (!addr.equals(reply.getAddress()) || reply.getPort() != server.port()) {
                throw new IOException("Unexpected relay source");
            }
            return Arrays.copyOf(received, reply.getLength());
            } finally { sockets.remove(socket); }
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "TUN diagnostic", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Only an isolated test destination is routed; not a full VPN");
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DiagnosticVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo)
                .setContentTitle("ADBlock TUN diagnostic")
                .setContentText("Scoped tests 53535 Echo / 53536 local UDP / 53537 DNS")
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private synchronized void shutdown() {
        stopped = true;
        RUNNING.compareAndSet(this, null);
        dnsPermits.close();
        sockets.close();
        if (pump != null) pump.close();
        if (reader != null) reader.interrupt();
        try { if (writerOutput != null) writerOutput.close(); } catch (IOException ignored) { }
        try { if (writerFd != null) writerFd.close(); } catch (IOException ignored) { }
        if (udpLoopback != null) {
            udpLoopback.close();
            udpLoopback = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException exception) {
                Timber.w(exception, "Diagnostic TUN close failed");
            }
            tun = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onRevoke() {
        shutdown();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
