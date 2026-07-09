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
    /**
     * The launcher to pick a custom block-placeholder image.
     */
    private ActivityResultLauncher<String> pickBlockImageLauncher;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Configure preferences
        getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
        addPreferencesFromResource(R.xml.preferences_root);
        // Register for activities
        registerForOpenHostActivity();
        registerForPrepareCertificateActivity();
        registerForPickBlockImageActivity();
        // Bind pref actions
        bindOpenHostsFile();
        bindRedirection();
        bindWebServerPrefAction();
        bindWebServerTest();
        bindWebServerCertificate();
        bindWebServerBlockImage();
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

    private void registerForPickBlockImageActivity() {
        this.pickBlockImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    Context ctx = requireContext();
                    boolean ok = org.adaway.util.WebServerUtils.setCustomBlockImage(ctx, uri);
                    android.widget.Toast.makeText(
                            ctx,
                            ok ? R.string.pref_webserver_block_image_success
                               : R.string.pref_webserver_block_image_failed,
                            android.widget.Toast.LENGTH_LONG
                    ).show();
                }
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
             * The "install as system CA" root-shell path
             * (installSystemCertificate) went through three rounds of
             * fixes this session (an unreachable-on-Android-11+ gate, a
             * missing openssl CLI dependency, a suspected SELinux label
             * mismatch after the tmpfs mount) without a confirmed working
             * result on-device. Removed it rather than keep chasing it -
             * the complexity and root-remount risk weren't earning their
             * keep against an unproven benefit. Always use the file-export
             * + Settings-install flow now; it's the well-understood,
             * reliable path (this is what every device got by default
             * before the system-CA dialog was ever added). The web server
             * status summary still reports whether the cert has separately
             * ended up trusted at the system level (e.g. via a Magisk
             * "move certificates"-style module) - see getWebServerState().
             */
            this.prepareCertificateLauncher.launch("adaway-webserver-certificate.crt");
            return true;
        });
    }

    private void bindWebServerBlockImage() {
        Preference blockImagePref = findPreference(getString(R.string.pref_webserver_block_image_key));
        assert blockImagePref != null : PREFERENCE_NOT_FOUND;
        blockImagePref.setOnPreferenceClickListener(preference -> {
            Context ctx = requireContext();
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.pref_webserver_block_image)
                    .setItems(new CharSequence[]{
                            getString(R.string.pref_webserver_block_image_choose),
                            getString(R.string.pref_webserver_block_image_reset)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            this.pickBlockImageLauncher.launch("image/*");
                        } else {
                            AppExecutors.getInstance().diskIO().execute(() -> {
                                org.adaway.util.WebServerUtils.resetBlockImagesToDefault(ctx);
                                AppExecutors.getInstance().mainThread().execute(() ->
                                        android.widget.Toast.makeText(
                                                ctx,
                                                R.string.pref_webserver_block_image_reset_success,
                                                android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                );
                            });
                        }
                    })
                    .show();
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
                    int summaryResId = getWebServerState(requireContext());
                    executors.mainThread().execute(
                            () -> webServerTest.setSummary(summaryResId)
                    );
                }
        );
    }
}
