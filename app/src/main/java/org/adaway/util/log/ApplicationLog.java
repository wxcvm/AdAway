package org.adaway.util.log;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.topjohnwu.superuser.Shell;

import org.adaway.helper.PreferenceHelper;

import timber.log.Timber;

/**
 * This class is an utility class that configures the application log.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class ApplicationLog {
    /**
     * Private constructor.
     */
    private ApplicationLog() {

    }

    /**
     * Initialize application logging.
     *
     * @param application The application instance.
     */
    public static void init(Application application) {
        if (isApplicationDebuggable(application) || PreferenceHelper.getDebugEnabled(application)) {
            Shell.enableVerboseLogging = true;
            Timber.plant(new Timber.DebugTree());
        } else {
            Shell.enableVerboseLogging = false;
            /*
             * BUG FIX: release builds used to plant no Timber tree at all
             * here, so logcat was silent in exactly the builds where
             * diagnosing a crash or a failed hosts update matters most.
             * Always log to logcat; only verbose debug-level output is
             * gated behind the debug preference.
             */
            Timber.plant(new ReleaseLogTree());
            SentryLog.init(application);
        }
    }

    /**
     * Log to logcat in release builds, filtered to warnings and above to
     * avoid noise (and any incidental information leakage) from verbose
     * debug output.
     */
    private static class ReleaseLogTree extends Timber.DebugTree {
        @Override
        protected boolean isLoggable(String tag, int priority) {
            return priority >= android.util.Log.WARN;
        }
    }

    private static boolean isApplicationDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
