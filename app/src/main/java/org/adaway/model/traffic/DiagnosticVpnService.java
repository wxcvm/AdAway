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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.adaway.R;
import org.adaway.model.root.HijackModel;
import org.adaway.ui.compose.MainActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;

import timber.log.Timber;

/**
 * Explicitly opt-in diagnostic TUN, *not* a full-traffic VPN.
 *
 * Only routes 198.18.0.1/32 to this service. It echoes valid IPv4 UDP
 * packets to port 53535 and relays small packets addressed to port 53536
 * through a protected socket to a device-local UDP responder. No Internet
 * forwarding or general DNS is enabled.
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
    private static final AtomicLong PACKET_COUNT = new AtomicLong();
    private static final AtomicLong ECHO_COUNT = new AtomicLong();
    private static volatile boolean active = false;
    private volatile boolean stopped = false;
    private ParcelFileDescriptor tun;
    private Thread reader;
    private DiagnosticLoopbackUdpServer udpLoopback;
    private volatile long lastRelayAtMs = -200L;
    private static final AtomicLong RELAY_COUNT = new AtomicLong();

    public static boolean isActive() {
        return active;
    }

    public static long getPacketCount() {
        return PACKET_COUNT.get();
    }

    public static long getEchoCount() {
        return ECHO_COUNT.get();
    }

    public static long getRelayCount() {
        return RELAY_COUNT.get();
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
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        try {
            createChannel();
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            // Revocation, Root transparent redirect and an active foreign VPN
            // are separate conflict checks. Never request/replace VPN consent here.
            if (VpnService.prepare(this) != null
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
                    .setBlocking(true) // the packet reader uses blocking reads
                    .setMtu(DiagnosticTunPlan.MTU)
                    .addAddress(DiagnosticTunPlan.LOCAL_ADDRESS,
                            DiagnosticTunPlan.LOCAL_PREFIX);
            if (!DiagnosticTunPlan.isSafeRoute(DiagnosticTunPlan.DIAGNOSTIC_TARGET,
                    DiagnosticTunPlan.TARGET_PREFIX)) {
                throw new IllegalStateException("Unsafe diagnostic route");
            }
            builder.addRoute(DiagnosticTunPlan.DIAGNOSTIC_TARGET,
                    DiagnosticTunPlan.TARGET_PREFIX);
            tun = builder.establish();
            if (tun == null) {
                Timber.w("Diagnostic VPN establishment failed or consent revoked");
                shutdown();
                return START_NOT_STICKY;
            }
            stopped = false;
            PACKET_COUNT.set(0L);
            ECHO_COUNT.set(0L);
            RELAY_COUNT.set(0L);
            udpLoopback = new DiagnosticLoopbackUdpServer();
            active = true;
            ParcelFileDescriptor fd = tun;
            reader = new Thread(() -> readDiagnosticPackets(fd), "diagnostic-tun-reader");
            reader.setDaemon(true);
            reader.start();
            Timber.i("Diagnostic TUN up: only %s/%d routed (no forwarding)",
                    DiagnosticTunPlan.DIAGNOSTIC_TARGET, DiagnosticTunPlan.TARGET_PREFIX);
        } catch (IOException | RuntimeException exception) {
            Timber.w(exception, "Cannot start diagnostic TUN");
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void readDiagnosticPackets(ParcelFileDescriptor fd) {
        // Separate descriptor ownership for the reader and writer: closing the
        // output stream must not prematurely invalidate a still-active input.
        try (ParcelFileDescriptor writerFd = fd.dup();
             FileInputStream input = new FileInputStream(fd.getFileDescriptor());
             FileOutputStream output = new FileOutputStream(writerFd.getFileDescriptor())) {
            byte[] packet = new byte[2048];
            while (!stopped) {
                int length = input.read(packet);
                if (length < 0) break;
                if (length == 0) continue;
                PACKET_COUNT.incrementAndGet();
                byte[] response = DiagnosticUdpEcho.reply(packet, length);
                if (response != null) {
                    output.write(response);
                    ECHO_COUNT.incrementAndGet();
                    continue;
                }
                // A second explicit test-only port crosses a protected
                // DatagramSocket to our loopback server and back. A single
                // synchronous worker and cooldown bound the amount of work.
                long now = SystemClock.elapsedRealtime();
                if (now - lastRelayAtMs < 200) continue;
                lastRelayAtMs = now;
                response = DiagnosticUdpRelay.process(packet, length, this::relayLocally);
                if (response != null) {
                    output.write(response);
                    RELAY_COUNT.incrementAndGet();
                }
            }
        } catch (IOException exception) {
            if (!stopped) Timber.w(exception, "Diagnostic TUN read failed");
        } finally {
            active = false;
            if (!stopped) stopSelf();
        }
    }

    private byte[] relayLocally(byte[] payload) throws IOException {
        DiagnosticLoopbackUdpServer server = udpLoopback;
        if (server == null || stopped) throw new IOException("Relay not running");
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket socket = new DatagramSocket()) {
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
                .setContentText("Scoped test TUN: direct Echo 53535 / loopback UDP relay 53536")
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private synchronized void shutdown() {
        stopped = true;
        active = false;
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
