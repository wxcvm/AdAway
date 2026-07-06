package org.adaway.ui.prefs;

import static android.provider.Settings.ACTION_SECURITY_SETTINGS;
import static android.widget.Toast.LENGTH_SHORT;
import static org.adaway.model.root.MountType.READ_ONLY;
import static org.adaway.model.root.MountType.READ_WRITE;
import static org.adaway.model.root.ShellUtils.isWritable;
import static org.adaway.model.root.ShellUtils.remountPartition;
import static org.adaway.ui.prefs.PrefsActivity.PREFERENCE_NOT_FOUND;
import static org.adaway.util.Constants.ANDROID_SYSTEM_ETC_HOSTS;
import static org.adaway.util.Constants.PREFS_NAME;
import static org.adaway.util.WebServerUtils.TEST_URL;
import static org.adaway.util.WebServerUtils.copyCertificate;
import static org.adaway.util.WebServerUtils.getWebServerState;
import static org.adaway.util.WebServerUtils.installSystemCertificate;
import static org.adaway.util.WebServerUtils.isSystemCertificateInstalled;
import static org.adaway.util.WebServerUtils.isWebServerRunning;
import static org.adaway.util.WebServerUtils.startWebServer;
import static org.adaway.util.WebServerUtils.stopWebServer;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.net.InetAddresses;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.adaway.ui.dialog.MissingAppDialog;
import org.adaway.util.AppExecutors;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import timber.log.Timber;

/**
 * This fragment is the preferences fragment for root ad blocker.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class PrefsRootFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    /**
     * The webserver certificate mime type.
     */
    private static final String CERTIFICATE_MIME_TYPE = "application/x-x509-ca-cert";
    /**
     * The launcher to start open hosts file activity.
     */
    private ActivityResultLauncher<Intent> openHostsFileLauncher;
    /**
     * The launcher to prepare web service certificate activity.
     */
    private ActivityResultLauncher<String> prepareCertificateLauncher;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Configure preferences
        getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
        addPreferencesFromResource(R.xml.preferences_root);
        // Register for activities
        registerForOpenHostActivity();
        registerForPrepareCertificateActivity();
        // Bind pref actions
        bindOpenHostsFile();
        bindRedirection();
        bindWebServerPrefAction();
        bindWebServerTest();
        bindWebServerCertificate();
        // Update current state
        updateWebServerState();
        // Register as listener
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        PrefsActivity.setAppBarTitle(this, R.string.pref_root_title);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Update current state
        updateWebServerState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Unregister as listener
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Intentionally left without special handling: the only entry that
        // used to live here (webserverIcon) was removed - see
        // PreferenceHelper.getWebServerIcon() removal for why.
    }

    private void registerForOpenHostActivity() {
        this.openHostsFileLauncher = registerForActivityResult(new StartActivityForResult(), result -> {
            try {
                File hostFile = new File(ANDROID_SYSTEM_ETC_HOSTS).getCanonicalFile();
                remountPartition(hostFile, READ_ONLY);
            } catch (IOException e) {
                Timber.e(e, "Failed to get hosts canonical file.");
            }
        });
    }

    private void registerForPrepareCertificateActivity() {
        this.prepareCertificateLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(CERTIFICATE_MIME_TYPE),
                this::prepareWebServerCertificate
        );
    }

    private void bindOpenHostsFile() {
        Preference openHostsFilePreference = findPreference(getString(R.string.pref_open_hosts_key));
        assert openHostsFilePreference != null : PREFERENCE_NOT_FOUND;
        openHostsFilePreference.setOnPreferenceClickListener(this::openHostsFile);
    }

    private boolean openHostsFile(Preference preference) {
        try {
            File hostFile = new File(ANDROID_SYSTEM_ETC_HOSTS).getCanonicalFile();
            boolean remount = !isWritable(hostFile) && remountPartition(hostFile, READ_WRITE);
            Intent intent = new Intent()
                    .setAction(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("file://" + hostFile.getAbsolutePath()), "text/plain");
            if (remount) {
                this.openHostsFileLauncher.launch(intent);
            } else {
                startActivity(intent);
            }
            return true;
        } catch (IOException e) {
            Timber.e(e, "Failed to get hosts canonical file.");
        } catch (ActivityNotFoundException e) {
            MissingAppDialog.showTextEditorMissingDialog(getContext());
            return false;
        }
        return false;
    }

    private void bindRedirection() {
        Context context = requireContext();
        boolean ipv6Enabled = PreferenceHelper.getEnableIpv6(context);
        Preference ipv4RedirectionPreference = findPreference(getString(R.string.pref_redirection_ipv4_key));
        assert ipv4RedirectionPreference != null : PREFERENCE_NOT_FOUND;
        ipv4RedirectionPreference.setOnPreferenceChangeListener(
                (preference, newValue) -> validateRedirection(Inet4Address.class, (String) newValue)
        );
        Preference ipv6RedirectionPreference = findPreference(getString(R.string.pref_redirection_ipv6_key));
        assert ipv6RedirectionPreference != null : PREFERENCE_NOT_FOUND;
        ipv6RedirectionPreference.setEnabled(ipv6Enabled);
        ipv6RedirectionPreference.setOnPreferenceChangeListener(
                (preference, newValue) -> validateRedirection(Inet6Address.class, (String) newValue)
        );
    }

    private boolean validateRedirection(Class<? extends InetAddress> addressType, String redirection) {
        boolean valid;
        try {
            InetAddress inetAddress = InetAddresses.forString(redirection);
            valid = addressType.isAssignableFrom(inetAddress.getClass());
        } catch (IllegalArgumentException exception) {
            valid = false;
        }
        if (!valid) {
            Toast.makeText(requireContext(), R.string.pref_redirection_invalid, LENGTH_SHORT).show();
        }
        return valid;
    }

    private void bindWebServerPrefAction() {
        Context context = requireContext();
        SwitchPreferenceCompat webServerEnabledPref = findPreference(getString(R.string.pref_webserver_enabled_key));
        assert webServerEnabledPref != null : PREFERENCE_NOT_FOUND;
        webServerEnabledPref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enable = Boolean.TRUE.equals(newValue);
            // BUG FIX: starting the bundled webserver goes through a root
            // shell (Shell.cmd().exec() — blocking) and the forked process
            // then needs real time to generate/load its TLS certificate
            // (RSA-2048 keygen) and bind sockets before `ps` can see it.
            // The old code called isWebServerRunning() synchronously,
            // microseconds after backgrounding the process, which was
            // ALWAYS false — so the Preference framework instantly
            // reverted the switch back to OFF on every single attempt,
            // making the feature look completely broken.
            //
            // Fix: accept the toggle immediately (no UI jank, no ANR risk
            // from blocking root calls on the main thread), do the actual
            // start/stop + verification on a background thread with a
            // short retry/poll window, then correct the switch afterwards
            // if it didn't actually take.
            AppExecutors executors = AppExecutors.getInstance();
            executors.diskIO().execute(() -> {
                boolean success;
                if (enable) {
                    startWebServer(context);
                    success = waitForWebServerState(true);
                } else {
                    stopWebServer();
                    success = waitForWebServerState(false);
                }
                executors.mainThread().execute(() -> {
                    if (!success) {
                        webServerEnabledPref.setChecked(!enable);
                        Toast.makeText(
                                context,
                                enable
                                        ? R.string.pref_webserver_start_failed
                                        : R.string.pref_webserver_stop_failed,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                    updateWebServerState();
                });
            });
            // Accept the change optimistically; corrected above if it fails.
            return true;
        });
    }

    /**
     * Poll {@link org.adaway.util.WebServerUtils#isWebServerRunning()} for up
     * to ~3 seconds. The bundled executable is forked via a root shell and
     * needs real time to fork, generate or load its certificate, and bind
     * its listening sockets before it becomes observable via {@code ps}.
     *
     * @param expectedRunning {@code true} when waiting for the server to
     *                        come up, {@code false} when waiting for it to
     *                        fully stop.
     * @return whether the expected state was reached within the timeout.
     */
    private boolean waitForWebServerState(boolean expectedRunning) {
        for (int attempt = 0; attempt < 6; attempt++) {
            if (isWebServerRunning() == expectedRunning) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isWebServerRunning() == expectedRunning;
    }

    private void bindWebServerTest() {
        Preference webServerTest = findPreference(getString(R.string.pref_webserver_test_key));
        assert webServerTest != null : PREFERENCE_NOT_FOUND;
        webServerTest.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(TEST_URL));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // BUG FIX: no app could resolve the implicit ACTION_VIEW
                // intent for TEST_URL (e.g. no browser installed, or the
                // intent is filtered out by package-visibility rules on
                // Android 11+). Previously this threw an uncaught
                // ActivityNotFoundException here, crashing the activity
                // and kicking the user all the way back to the home
                // screen (see issue #2). Catch it and show a toast
                // instead, matching how openHostsFile() already handles
                // the same class of failure.
                Timber.e(e, "No application found to open the web server test URL.");
                Toast.makeText(
                        requireContext(),
                        R.string.pref_webserver_test_no_browser,
                        Toast.LENGTH_LONG
                ).show();
            }
            return true;
        });
    }

    private void bindWebServerCertificate() {
        Preference webServerTest = findPreference(getString(R.string.pref_webserver_certificate_key));
        assert webServerTest != null : PREFERENCE_NOT_FOUND;
        webServerTest.setOnPreferenceClickListener(preference -> {
            /*
             * BUG FIX: this used to gate the whole system-CA-install dialog
             * behind `SDK_INT < VERSION_CODES.R` (Android 11), so on every
             * device running Android 11+ — the overwhelming majority of
             * devices today — this branch was unreachable and users always
             * fell straight to the file-export flow below, which can only
             * ever produce a *user* certificate (shows up under "User
             * credentials", never "Trusted credentials").
             *
             * installSystemCertificate() itself is entirely root-shell
             * based (mount/cp via `su`, not any official Android KeyChain
             * API), and already has its own internal branch for Android
             * 14+ (tmpfs overlay) vs older versions (direct remount) — it
             * was clearly updated to support modern Android after this
             * SDK_INT gate was written, but the gate itself was never
             * updated to match, leaving a fully working, modern
             * implementation unreachable from the UI. This whole screen
             * already assumes root access (it's the "root based ad
             * blocker" screen), so there's no reason to restrict the
             * root-based system-install path by Android version at all —
             * offer it unconditionally.
             */
            /*
             * BUG FIX: this used to call installUserCertificate(ctx), which
             * goes through KeyChain.createInstallIntent() to install the CA
             * cert directly. On this device (and apparently others), that
             * flow gets rejected outright by the system with "Unable to
             * install CA certificate ... must be installed through
             * Settings" - confirmed on-device, not a hypothesis. That's
             * presumably exactly why this file-export flow
             * (prepareCertificateLauncher) existed in the first place
             * before the SDK_INT gate above was removed - it was the
             * correct fallback for exactly this restriction, just gated
             * off for the wrong set of devices. Use it here instead.
             */
            Context ctx = requireContext();
            if (isSystemCertificateInstalled(ctx)) {
                this.prepareCertificateLauncher.launch("adaway-webserver-certificate.crt");
            } else {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.pref_webserver_certificate_dialog_title)
                    .setMessage(R.string.pref_webserver_system_ca_prompt)
                    .setPositiveButton(R.string.pref_webserver_system_ca_install, (d, w) -> {
                        boolean ok = installSystemCertificate(ctx);
                        int msg = ok ? R.string.pref_webserver_system_ca_success
                                     : R.string.pref_webserver_system_ca_failed;
                        android.widget.Toast.makeText(ctx, msg,
                            android.widget.Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton(R.string.pref_webserver_certificate_user_only,
                        (d, w) -> this.prepareCertificateLauncher.launch("adaway-webserver-certificate.crt"))
                    .show();
            }
            return true;
        });
    }

    private void prepareWebServerCertificate(Uri uri) {
        // Check user selected document
        if (uri == null) {
            return;
        }
        Timber.d("Certificate URI: %s", uri);
        copyCertificate(requireActivity(), uri);
        new MaterialAlertDialogBuilder(requireContext())
                .setCancelable(true)
                .setTitle(R.string.pref_webserver_certificate_dialog_title)
                .setMessage(R.string.pref_webserver_certificate_dialog_content)
                .setPositiveButton(
                        R.string.pref_webserver_certificate_dialog_action,
                        (dialog, which) -> {
                            dialog.dismiss();
                            Intent intent = new Intent(ACTION_SECURITY_SETTINGS);
                            startActivity(intent);
                        })
                .create()
                .show();
    }

    private void updateWebServerState() {
        Preference webServerTest = findPreference(getString(R.string.pref_webserver_test_key));
        assert webServerTest != null : PREFERENCE_NOT_FOUND;
        webServerTest.setSummary(R.string.pref_webserver_state_checking);
        AppExecutors executors = AppExecutors.getInstance();
        executors.networkIO().execute(() -> {
                    // Wait for server to start
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    int summaryResId = getWebServerState();
                    executors.mainThread().execute(
                            () -> webServerTest.setSummary(summaryResId)
                    );
                }
        );
    }
}
