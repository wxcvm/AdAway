package org.adaway.broadcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.adaway.ui.compose.MainActivity;
import org.adaway.util.WebServerUtils;

import timber.log.Timber;

/**
 * Optional foreground service that keeps the intercept server alive.
 *
 * <p>The normal safety net ({@link ServerWatchdogWorker}) is WorkManager based,
 * which means the system may defer it by many minutes - on OEM ROMs that delay
 * background work, "the server died" can stay true for a long time. A
 * foreground service is not deferred: it holds the process and can restart the
 * server within seconds.</p>
 *
 * <p>The price is a permanent notification, so this is strictly <b>opt in</b>
 * ({@code keepalive_enabled}): the user decides whether a persistent
 * notification is worth the faster recovery. Turning the setting off (or
 * switching the web server itself off) stops the service again - the check
 * below re-reads the preference on every tick, so a stale service cannot
 * outlive the setting.</p>
 *
 * <p>Every start path is defensive: a foreground start can be refused by the
 * platform (background-start restrictions, notifications disabled, an
 * unexpected service type), and being refused must never crash the app - it
 * just falls back to the WorkManager watchdog that already exists.</p>
 */
public class ServerKeepAliveService extends Service {

    /** Start the service (idempotent). */
    public static final String ACTION_START = "org.adaway.action.KEEPALIVE_START";
    /** Stop the service from inside itself. */
    public static final String ACTION_STOP = "org.adaway.action.KEEPALIVE_STOP";

    /** Notification channel of the persistent notification. */
    public static final String CHANNEL_ID = "KeepAliveChannel";

    private static final int NOTIFICATION_ID = 4712;
    /** How often the server is verified while the service runs. */
    private static final long CHECK_INTERVAL_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkTask = new Runnable() {
        @Override
        public void run() {
            checkOnce();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    /**
     * Ask the system to run (or keep running) the keep-alive service.
     *
     * @param context Any context; the application context is used.
     */
    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, ServerKeepAliveService.class)
                    .setAction(ACTION_START);
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable throwable) {
            // Background-start restrictions on some ROMs: the WorkManager
            // watchdog stays the fallback, so never let this reach the user.
            Timber.w(throwable, "keep-alive: could not start the foreground service");
        }
    }

    /**
     * Stop the keep-alive service.
     *
     * @param context Any context.
     */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, ServerKeepAliveService.class));
        } catch (Throwable throwable) {
            Timber.w(throwable, "keep-alive: could not stop the foreground service");
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        boolean stopRequested = intent != null && ACTION_STOP.equals(intent.getAction());
        if (stopRequested || !PreferenceHelper.getKeepAliveEnabled(this)) {
            // Either an explicit stop or the setting is (no longer) on: a
            // foreground service must not outlive the user's decision.
            handler.removeCallbacks(checkTask);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        promoteToForeground();
        handler.removeCallbacks(checkTask);
        handler.post(checkTask);
        // START_STICKY: if the system kills us for memory, come back.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkTask);
        super.onDestroy();
    }

    /**
     * One liveness check: reachability first (a single socket connect), the root
     * process check only when that fails; the restart runs off the main thread
     * because it spawns a root shell.
     */
    private void checkOnce() {
        if (!PreferenceHelper.getKeepAliveEnabled(this)) {
            stop(this);
            return;
        }
        new Thread(() -> {
            try {
                if (WebServerUtils.isWebServerReachable(this) || WebServerUtils.isWebServerRunning()) {
                    return;
                }
                Timber.i("keep-alive: server is gone - restarting it");
                WebServerUtils.startWebServer(this);
            } catch (Throwable throwable) {
                Timber.w(throwable, "keep-alive: check failed");
            }
        }, "keepalive-check").start();
    }

    private void promoteToForeground() {
        createChannel();
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable throwable) {
            Timber.w(throwable, "keep-alive: startForeground was refused - "
                    + "the WorkManager watchdog stays in charge");
            stopSelf();
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_keepalive_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo)
                .setContentTitle(getString(R.string.notification_keepalive_title))
                .setContentText(getString(R.string.notification_keepalive_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
