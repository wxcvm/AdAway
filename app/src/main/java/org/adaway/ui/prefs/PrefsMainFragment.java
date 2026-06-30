package org.adaway.ui.prefs;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.adaway.R;
import org.adaway.util.log.SentryLog;

import static org.adaway.ui.prefs.PrefsActivity.PREFERENCE_NOT_FOUND;
import static org.adaway.util.Constants.PREFS_NAME;

/**
 * This fragment is the preferences main fragment.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class PrefsMainFragment extends PreferenceFragmentCompat {
    private static final Uri REPO_LINK = Uri.parse("https://github.com/wxcvm/AdAway");
    private static final Uri ISSUES_LINK = Uri.parse("https://github.com/wxcvm/AdAway/issues");

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
        addPreferencesFromResource(R.xml.preferences_main);
        bindThemePrefAction();
        bindTelemetryPrefAction();
        bindAboutSection();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        PrefsActivity.setAppBarTitle(this, R.string.pref_main_title);
    }

    @Override
    public void onResume() {
        super.onResume();
        PrefsActivity.setAppBarTitle(this, R.string.pref_main_title);
    }

    private void bindThemePrefAction() {
        Preference darkThemePref = findPreference(getString(R.string.pref_dark_theme_mode_key));
        assert darkThemePref != null : PREFERENCE_NOT_FOUND;
        darkThemePref.setOnPreferenceChangeListener((preference, newValue) -> {
            requireActivity().recreate();
            return true;
        });
    }

    private void bindTelemetryPrefAction() {
        Preference enableTelemetryPref = findPreference(getString(R.string.pref_enable_telemetry_key));
        assert enableTelemetryPref != null : PREFERENCE_NOT_FOUND;
        enableTelemetryPref.setOnPreferenceChangeListener((preference, newValue) -> {
            SentryLog.setEnabled(requireActivity().getApplication(), (boolean) newValue);
            return true;
        });
        if (SentryLog.isStub()) {
            enableTelemetryPref.setEnabled(false);
            enableTelemetryPref.setSummary(R.string.pref_enable_telemetry_disabled_summary);
        }
    }

    /**
     * Binds the "About" footer section. This also keeps the settings screen
     * from trailing into a large empty void below the few preference items
     * above — a short PreferenceFragmentCompat list otherwise just leaves
     * raw background showing, which reads as broken/unfinished.
     */
    private void bindAboutSection() {
        Context context = requireContext();

        Preference repoPref = findPreference(getString(R.string.pref_about_repo_key));
        assert repoPref != null : PREFERENCE_NOT_FOUND;
        repoPref.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(Intent.ACTION_VIEW, REPO_LINK));
            return true;
        });

        Preference issuesPref = findPreference(getString(R.string.pref_about_issues_key));
        assert issuesPref != null : PREFERENCE_NOT_FOUND;
        issuesPref.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(Intent.ACTION_VIEW, ISSUES_LINK));
            return true;
        });

        Preference versionPref = findPreference(getString(R.string.pref_about_version_key));
        assert versionPref != null : PREFERENCE_NOT_FOUND;
        versionPref.setSummary(getVersionName(context));
    }

    private static String getVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
