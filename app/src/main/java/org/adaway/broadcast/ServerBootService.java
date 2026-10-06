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
import org.adaway.model.root.ShellUtils;
import org.adaway.ui.compose.MainActivity;
import org.adaway.util.WebServerUtils;

import timber.log.Timber;

/**
 * Bounded foreground service that starts the intercept server right after boot.
 *
 * <p>Why this exists: the WorkManager path ({@link ServerStartWorker},
 * {@link ServerWatchdogWorker}) is the safety net, but OEM ROMs routinely defer
 * queued jobs until the app is opened - which is exactly the reported symptom
 * "the server only starts a few seconds after I open the app". A foreground
 * service started from {@code BOOT_COMPLETED} is not deferred, so it can wait
 * for root, start the server and then <b>stop itself</b>: the notification is
 * only shown while the boot work runs (seconds to a couple of minutes), so the
 * permanent-notification question stays with the user's own keep-alive toggle
 * ({@link ServerKeepAliveService}) and is not forced on anyone.</p>
 *
 * <p>Every start path is defensive: a refused foreground start (background-start
 * restrictions, an unexpected service type) is logged and recorded, and the
 * receiver's WorkManager job stays the fallback - the app must never crash
 * because a ROM said no.</p>
 */
public class ServerBootService extends Service {

    /** Action used when the receiver asks for a boot start. */
    public static final String ACTION_START = "org.adaway.action.BOOT_START";

    /** Notification channel of the transient boot notification. */
    public static final String CHANNEL_ID = "BootChannel";

    private static final int NOTIFICATION_ID = 4713;

    /** Whole budget: after this the boot attempt gives up and hands over. */
    private static final long BUDGET_MS = 180_000L;

    /** Delay between two liveness/root checks. */
    private static final long POLL_MS = 2_000L;

    /** Mechanism name recorded in the boot diagnostics. */
    private static final String MECHANISM = "前台服务";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean finished;
    private long deadline;

    /**
     * Ask the system to run the boot service (no-op when it is already running).
     *
     * @param context Any context; the application context is used.
     */
    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, ServerBootService.class)
                    .setAction(ACTION_START);
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable throwable) {
            // Android 12+ refuses foreground starts from the background unless
            // the trigger is exempt; BOOT_COMPLETED is, but a ROM or a missing
            // exemption must not take the app down with it.
            Timber.w(throwable, "boot service: could not be started");
            PreferenceHelper.recordBootEvent(context,
                    PreferenceHelper.getLastBootAction(context), MECHANISM,
                    "前台服务被系统拒绝启动，已退回后台任务", false);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (!PreferenceHelper.getWebServerEnabled(this)) {
            PreferenceHelper.recordBootEvent(this, PreferenceHelper.getLastBootAction(this),
                    MECHANISM, "自动启动已关闭（设置 → 网络服务器）", false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!promoteToForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        deadline = System.currentTimeMillis() + BUDGET_MS;
        handler.post(poll);
        // Bounded work: a restarted copy would just repeat an attempt that is
        // already covered by the receiver's WorkManager fallback.
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    /**
     * One poll: reachable already -> done; budget over -> give up; otherwise
     * check root off the main thread and try to start the server.
     */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (finished) {
                return;
            }
            if (WebServerUtils.isWebServerReachable(ServerBootService.this)) {
                finish("前台服务已确认服务器运行", true);
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                finish("前台服务超时：root 未就绪或启动失败（详见开机脚本日志/后台任务）", false);
                return;
            }
            new Thread(() -> {
                try {
                    if (ShellUtils.isRootAvailable()) {
                        Timber.i("boot service: root is ready, starting the web server");
                        WebServerUtils.startWebServer(getApplicationContext());
                    } else {
                        Timber.d("boot service: root not ready yet, retrying");
                    }
                } catch (Throwable throwable) {
                    Timber.w(throwable, "boot service: start attempt failed");
                }
                handler.postDelayed(poll, POLL_MS);
            }, "adblock-boot-service").start();
        }
    };

    private void finish(String result, boolean ok) {
        if (finished) {
            return;
        }
        finished = true;
        handler.removeCallbacks(poll);
        // Uses the passed flag only: this runs on the main thread, and probing
        // the server here would mean a socket connect on it.
        PreferenceHelper.recordBootEvent(this, PreferenceHelper.getLastBootAction(this),
                MECHANISM, result, ok);
        Timber.i("boot service: %s", result);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean promoteToForeground() {
        createChannel();
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable throwable) {
            Timber.w(throwable, "boot service: startForeground was refused - "
                    + "the WorkManager fallback stays in charge");
            PreferenceHelper.recordBootEvent(this, PreferenceHelper.getLastBootAction(this),
                    MECHANISM, "前台服务无法前台化，已退回后台任务", false);
            return false;
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_boot_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notification_boot_channel_description));
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
                .setContentTitle(getString(R.string.notification_boot_title))
                .setContentText(getString(R.string.notification_boot_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
