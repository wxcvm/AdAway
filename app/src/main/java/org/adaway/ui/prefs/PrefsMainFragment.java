package org.adaway.ui.prefs;

import android.content.Context;
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
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
        addPreferencesFromResource(R.xml.preferences_main);
        bindThemePrefAction();
        bindTelemetryPrefAction();
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
}
