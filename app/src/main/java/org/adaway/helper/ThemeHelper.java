package org.adaway.helper;

import android.app.Activity;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;

/**
 * This class is a helper to apply user selected theme on the application activity.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class ThemeHelper {

    /**
     * Private constructor.
     */
    private ThemeHelper() {

    }

    /**
     * Apply the user selected theme.
     *
     * @param activity The activity to apply the theme to.
     */
    public static void applyTheme(Activity activity) {
        AppCompatDelegate.setDefaultNightMode(PreferenceHelper.getDarkThemeMode(activity));
        /*
         * BUG FIX: targetSdk 35 (Android 15) enforces edge-to-edge display by
         * default. None of this app's screens were migrated to handle
         * WindowInsets (padding their content around the status bar / the
         * ActionBar), so on API 35+ every screen's content draws underneath
         * the status bar and action bar instead of below them — visible
         * as the toolbar title overlapping the first list item on
         * HostsSourcesActivity/PrefsActivity and, likely, elsewhere.
         * Opt back out of edge-to-edge (restoring this app's pre-SDK-35
         * layout behavior) until each screen is properly migrated to
         * consume insets itself — that's a much larger, per-screen change
         * than belongs in this one-line fix.
         */
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true);
    }
}
