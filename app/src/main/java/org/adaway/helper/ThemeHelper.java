package org.adaway.helper;

import android.app.Activity;

import androidx.appcompat.app.AppCompatDelegate;

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
         * NOTE: the fix for the targetSdk-35 edge-to-edge toolbar/content
         * overlap does NOT belong here. A previous attempt called
         * WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true)
         * from this method, but that call is a deliberate no-op once
         * edge-to-edge is enforced (Android 15+ platform source literally
         * short-circuits it: `if (mEdgeToEdgeEnforced) return;`). The
         * actual, platform-honored opt-out is the theme attribute
         * android:windowOptOutEdgeToEdgeEnforcement, set on Base.AdAway /
         * Theme.AdAway.NoActionBar / Theme.AdAway.NoActionBar.Red in
         * values/styles.xml. See that file for the full explanation.
         */
    }
}
