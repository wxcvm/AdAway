package org.adaway.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import androidx.annotation.StringRes;

import org.adaway.R;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

import static org.adaway.model.root.ShellUtils.isBundledExecutableRunning;
import static org.adaway.model.root.ShellUtils.killBundledExecutable;
import static org.adaway.model.root.ShellUtils.runBundledExecutable;

import com.topjohnwu.superuser.Shell;

public class WebServerUtils {

    public static final String TEST_URL = "https://localhost/internal-test";
    private static final String WEB_SERVER_EXECUTABLE = "webserver";
    private static final String CA_CERT_FILE = "localhost-2410.crt";
    private static final String CA_KEY_FILE  = "localhost-2410.key";

    /**
     * Start the web server, killing any stale instance first and waiting for
     * the port to be released before relaunching.
     */
    public static void startWebServer(Context context) {
        Timber.d("Starting web server…");
        Path resourcePath = getResourcePath(context);
        ensureStaticResources(context, resourcePath);

        // Verify binary exists and is executable (using java.io.File for better compatibility)
        File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File binFile = new File(nativeLibDir, "lib" + WEB_SERVER_EXECUTABLE + "_exec.so");
        
        if (!binFile.exists()) {
            Timber.e("Webserver binary not found: %s", binFile.getAbsolutePath());
            Toast.makeText(context, "Webserver executable missing; please reinstall or check device compatibility.", Toast.LENGTH_LONG).show();
            return;
        }
        
        if (!binFile.canRead()) {
            Timber.e("Webserver binary is not readable: %s", binFile.getAbsolutePath());
            Toast.makeText(context, "Webserver binary is not readable; check permissions.", Toast.LENGTH_LONG).show();
            return;
        }

        // Kill any previous instance and wait for port 80/443 to be released.
        if (isBundledExecutableRunning(WEB_SERVER_EXECUTABLE)) {
            Timber.d("Stopping stale webserver instance before restart.");
            killBundledExecutable(WEB_SERVER_EXECUTABLE);
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        }

        String params = "--resources " + resourcePath.toAbsolutePath() + " --debug";
        boolean started = runBundledExecutable(context, WEB_SERVER_EXECUTABLE, params);
        if (!started) {
            Timber.e("Webserver failed to start; check logs in /data/local/tmp/webserver_start_*.log for details.");
            Toast.makeText(context, R.string.pref_webserver_start_failed, Toast.LENGTH_LONG).show();
        } else {
            Timber.i("Webserver started successfully");
        }
    }

    public static void stopWebServer() {
        killBundledExecutable(WEB_SERVER_EXECUTABLE);
    }

    public static boolean isWebServerRunning() {
        return isBundledExecutableRunning(WEB_SERVER_EXECUTABLE);
    }

    @StringRes
    public static int getWebServerState() {
        if (!isWebServerRunning()) return R.string.pref_webserver_state_not_running;

        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();

        try {
            try (Response r = client.newCall(
                    new Request.Builder().url("http://127.0.0.1/internal-test").build()
            ).execute()) {
                if (!r.isSuccessful()) return R.string.pref_webserver_state_not_running;
            }
        } catch (IOException e) {
            return R.string.pref_webserver_state_not_running;
        }

        try {
            try (Response r = client.newCall(
                    new Request.Builder().url(TEST_URL).build()
            ).execute()) {
                return r.isSuccessful()
                        ? R.string.pref_webserver_state_running_and_installed
                        : R.string.pref_webserver_state_running_not_installed;
            }
        } catch (IOException e) {
            return isSslError(e)
                    ? R.string.pref_webserver_state_running_not_installed
                    : R.string.pref_webserver_state_not_running;
        }
    }

    /**
     * Install AdAway's CA into the user trust store (KeyChain).
     * Works on all Android versions but requires the user to confirm.
     */
    public static void installUserCertificate(Context context) {
        Path certFile = getResourcePath(context).resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) {
            Toast.makeText(context, R.string.pref_webserver_certificate_enable_first,
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert;
            try (InputStream is = Files.newInputStream(certFile)) {
                cert = (X509Certificate) cf.generateCertificate(is);
            }
            Intent intent = android.security.KeyChain.createInstallIntent();
            intent.putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, cert.getEncoded());
            intent.putExtra(android.security.KeyChain.EXTRA_NAME, "AdAway CA");
            context.startActivity(intent);
        } catch (IOException | CertificateException e) {
            Timber.w(e, "Failed to prepare certificate for install.");
        }
    }

    /**
     * Install AdAway's CA into the SYSTEM trust store using root shell.
     * This makes AdAway's block-page HTTPS trusted by ALL apps (including VPN
     * tools) without any per-app or per-user confirmation dialog.
     *
     * Strategy:
     *   Android ≤ 13 : write directly to /system/etc/security/cacerts/
     *   Android 14+  : use tmpfs overlay (same technique as Magisk)
     *
     * @return true if installation succeeded.
     */
    public static boolean installSystemCertificate(Context context) {
        Path certFile = getResourcePath(context).resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) {
            Timber.w("CA cert not present — enable web server first.");
            return false;
        }
        String certPath = certFile.toAbsolutePath().toString();

        // Use OpenSSL to compute the subject_hash_old (trust-store filename)
        String hashResult = Shell.cmd(
                "openssl x509 -subject_hash_old -noout -in " + certPath
        ).exec().getOut().stream().findFirst().orElse("");

        if (hashResult.isEmpty()) {
            Timber.e("Failed to compute certificate hash.");
            return false;
        }
        String certHash = hashResult.trim();
        String destName = certHash + ".0";

        int sdk = android.os.Build.VERSION.SDK_INT;
        boolean ok;

        if (sdk >= 34) {
            // Android 14+: mount a tmpfs over /system/etc/security/cacerts,
            // copy existing certs into it, then add ours.
            ok = Shell.cmd(
                "mkdir -p /data/local/tmp/adaway_cacerts",
                "cp /system/etc/security/cacerts/* /data/local/tmp/adaway_cacerts/ 2>/dev/null || true",
                "cp " + certPath + " /data/local/tmp/adaway_cacerts/" + destName,
                "chmod 644 /data/local/tmp/adaway_cacerts/" + destName,
                "mount -t tmpfs tmpfs /system/etc/security/cacerts",
                "cp /data/local/tmp/adaway_cacerts/* /system/etc/security/cacerts/",
                "chown -R root:root /system/etc/security/cacerts",
                "chmod 644 /system/etc/security/cacerts/*",
                "rm -rf /data/local/tmp/adaway_cacerts"
            ).exec().isSuccess();
        } else {
            // Android ≤ 13: remount /system rw and write directly.
            ok = Shell.cmd(
                "mount -o remount,rw /system 2>/dev/null || true",
                "cp " + certPath + " /system/etc/security/cacerts/" + destName,
                "chmod 644 /system/etc/security/cacerts/" + destName,
                "chown root:root /system/etc/security/cacerts/" + destName,
                "mount -o remount,ro /system 2>/dev/null || true"
            ).exec().isSuccess();
        }

        if (ok) {
            Timber.i("System CA installed: /system/etc/security/cacerts/%s", destName);
            // Reload cert store (sends SIGUSR1 to system_server on some ROMs)
            Shell.cmd("am broadcast -a android.intent.action.CONFIGURATION_CHANGED")
                 .exec();
        } else {
            Timber.e("System CA installation failed.");
        }
        return ok;
    }

    /**
     * Check whether AdAway's CA is already present in the system trust store.
     */
    public static boolean isSystemCertificateInstalled(Context context) {
        Path certFile = getResourcePath(context).resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) return false;
        String certPath = certFile.toAbsolutePath().toString();
        String hash = Shell.cmd(
                "openssl x509 -subject_hash_old -noout -in " + certPath
        ).exec().getOut().stream().findFirst().orElse("").trim();
        if (hash.isEmpty()) return false;
        return Shell.cmd("test -f /system/etc/security/cacerts/" + hash + ".0")
                    .exec().isSuccess();
    }

    public static void copyCertificate(ContextThemeWrapper wrapper, Uri uri) {
        Path certFile = getResourcePath(wrapper).resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) {
            Toast.makeText(wrapper, R.string.pref_webserver_certificate_enable_first,
                    Toast.LENGTH_LONG).show();
            return;
        }
        ContentResolver cr = wrapper.getContentResolver();
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Cannot open " + uri);
            Files.copy(certFile, os);
        } catch (IOException e) {
            Timber.w(e, "Failed to copy certificate.");
        }
    }

    public static Path getResourcePath(Context context) {
        return context.getFilesDir().toPath().resolve(WEB_SERVER_EXECUTABLE);
    }

    // ── Private helpers ──────────────────────────────────────────

    private static void ensureStaticResources(Context context, Path target) {
        android.content.res.AssetManager am = context.getAssets();
        try {
            if (!Files.isDirectory(target)) Files.createDirectories(target);
            inflateResource(am, "test.html", target);
            for (int i = 0; i < 7; i++)
                inflateResource(am, String.format("img_%02d.webp", i), target);
        } catch (IOException e) {
            Timber.w(e, "Failed to inflate web server resources.");
        }
        // Delete stale cert (missing SAN / EKU) so server regenerates on next start
        deleteStaleServerCert(target);
    }

    private static void deleteStaleServerCert(Path dir) {
        Path certFile = dir.resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) return;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert;
            try (InputStream is = Files.newInputStream(certFile)) {
                cert = (X509Certificate) cf.generateCertificate(is);
            }
            boolean hasSan = cert.getSubjectAlternativeNames() != null
                    && !cert.getSubjectAlternativeNames().isEmpty();
            java.util.List<String> eku = cert.getExtendedKeyUsage();
            boolean hasServerAuth = eku != null && eku.contains("1.3.6.1.5.5.7.3.1");
            boolean[] ku = cert.getKeyUsage();
            boolean hasDigitalSig = ku != null && ku[0];

            if (!hasSan || !hasServerAuth || !hasDigitalSig) {
                Timber.d("Stale CA cert detected — deleting for regeneration.");
                Files.deleteIfExists(certFile);
                Files.deleteIfExists(dir.resolve(CA_KEY_FILE));
            }
        } catch (Exception e) {
            Timber.w(e, "Could not inspect cert; deleting for safety.");
            try { Files.deleteIfExists(certFile); Files.deleteIfExists(dir.resolve(CA_KEY_FILE)); }
            catch (IOException ignored) {}
        }
    }

    private static void inflateResource(android.content.res.AssetManager am,
                                        String name, Path target) throws IOException {
        Path out = target.resolve(name);
        if (!Files.isRegularFile(out)) Files.copy(am.open(name), out);
    }

    private static boolean isSslError(Throwable t) {
        while (t != null) {
            if (t instanceof javax.net.ssl.SSLException
                    || t instanceof java.security.cert.CertificateException
                    || t instanceof java.security.cert.CertPathValidatorException)
                return true;
            t = t.getCause();
        }
        return false;
    }
}
