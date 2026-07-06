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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
     * BUG FIX: Toast.show() must run on the main thread. startWebServer()
     * is invoked from RootModel.syncPreferences(), which runs on a
     * background disk-IO executor every time the app is opened (if the
     * user has the web server preference enabled). Calling Toast.show()
     * directly there throws an uncaught
     * android.view.ViewRootImpl$CalledFromWrongThreadException, crashing
     * the app on every launch. Route all toasts in this file through the
     * main looper instead.
     */
    private static void showToast(Context context, @StringRes int resId) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> Toast.makeText(context, resId, Toast.LENGTH_LONG).show());
    }

    private static void showToast(Context context, String text) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

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
            showToast(context, "Webserver executable missing; please reinstall or check device compatibility.");
            return;
        }
        
        if (!binFile.canRead()) {
            Timber.e("Webserver binary is not readable: %s", binFile.getAbsolutePath());
            showToast(context, "Webserver binary is not readable; check permissions.");
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
            showToast(context, R.string.pref_webserver_start_failed);
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
    public static int getWebServerState(Context context) {
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

        /*
         * BUG FIX: previously determined "is the cert installed" by
         * actually performing an HTTPS handshake (HttpsURLConnection) and
         * relying on network_security_config's per-domain trust-anchor
         * override to correctly consult both the system and user cert
         * stores. Confirmed on a real device this doesn't reliably
         * reflect reality: opening the exact same test URL in a real
         * browser (a separate process) worked perfectly with no
         * certificate warning at all, while this check kept reporting
         * "not installed" even after fully force-closing and restarting
         * AdAway itself (ruling out any per-process trust-manager
         * caching as the explanation). Whatever the exact platform/OEM
         * reason HttpsURLConnection wasn't picking this up, don't depend
         * on live OS-level TLS trust resolution for a yes/no answer we
         * can determine more directly and reliably: check whether a file
         * matching our CA's hash exists in either the system or user
         * trust-anchor directory - the same direct approach
         * isSystemCertificateInstalled() already used successfully for
         * the system side.
         */
        boolean installed = isSystemCertificateInstalled(context)
                || isUserCertificateInstalled(context);
        return installed
                ? R.string.pref_webserver_state_running_and_installed
                : R.string.pref_webserver_state_running_not_installed;
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

        /*
         * BUG FIX: this used to shell out to `openssl x509 -subject_hash_old`
         * to compute the trust-store filename. That's an external binary
         * this app doesn't control the presence of — modern Android's
         * crypto lives in the Conscrypt APEX module as libraries, not
         * necessarily as a general-purpose CLI tool, and plenty of vendor
         * images don't ship a standalone `openssl` command at all. When it
         * was missing, Shell.cmd(...) just returned an empty result, this
         * silently returned false, and the user saw an opaque "System CA
         * install failed" with nothing actionable in the visible UI.
         * Compute the same value in pure Java instead - no external
         * dependency, and a real exception if something's actually wrong
         * with the certificate file itself.
         */
        String certHash;
        try {
            certHash = computeSubjectHashOld(certFile);
        } catch (IOException | CertificateException | NoSuchAlgorithmException e) {
            Timber.e(e, "Failed to compute certificate hash.");
            return false;
        }
        String destName = certHash + ".0";

        int sdk = android.os.Build.VERSION.SDK_INT;
        boolean ok;

        if (sdk >= 34) {
            /*
             * BUG FIX (best-effort, not confirmed via on-device SELinux
             * denial logs): none of these commands ever set an SELinux
             * label on the files this creates. Mounting a fresh tmpfs over
             * /system/etc/security/cacerts replaces the directory's
             * contents with files that get a generic/inherited label, not
             * the specific type Android's certificate loading code
             * (running as system_server, in a tightly confined SELinux
             * domain) is allowed to read. The files can be perfectly
             * present, correctly named and permissioned, and still be
             * silently invisible to the OS purely because of this label
             * mismatch - "cp worked, chmod worked, toast said success" is
             * exactly what you'd see either way. Capture the directory's
             * actual context *before* wiping it with the tmpfs mount, and
             * restore that same context to the new files afterward -
             * whatever the correct value is on this specific device/vendor
             * image, rather than guessing a hardcoded SELinux type that
             * might be wrong for this ROM.
             */
            ok = Shell.cmd(
                "mkdir -p /data/local/tmp/adaway_cacerts",
                "cp /system/etc/security/cacerts/* /data/local/tmp/adaway_cacerts/ 2>/dev/null || true",
                "cp " + certPath + " /data/local/tmp/adaway_cacerts/" + destName,
                "chmod 644 /data/local/tmp/adaway_cacerts/" + destName,
                "SEL_CTX=$(stat -c '%C' /system/etc/security/cacerts 2>/dev/null)",
                "mount -t tmpfs tmpfs /system/etc/security/cacerts",
                "cp /data/local/tmp/adaway_cacerts/* /system/etc/security/cacerts/",
                "chown -R root:root /system/etc/security/cacerts",
                "chmod 644 /system/etc/security/cacerts/*",
                "[ -n \"$SEL_CTX\" ] && chcon -R \"$SEL_CTX\" /system/etc/security/cacerts || true",
                "rm -rf /data/local/tmp/adaway_cacerts"
            ).exec().isSuccess();
        } else {
            // Android ≤ 13: remount /system rw and write directly.
            // (Files created here inherit the existing directory's context
            // rather than a fresh tmpfs's, so this path isn't affected by
            // the same issue - no chcon needed.)
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
    /**
     * Check whether AdAway's CA is installed as a *user* trust anchor by
     * looking directly at the on-disk file Android's KeyChain writes
     * user-installed CA certs to, rather than performing a live HTTPS
     * handshake and hoping network_security_config's per-domain trust
     * override kicks in. See the comment in getWebServerState() for why.
     */
    public static boolean isUserCertificateInstalled(Context context) {
        Path certFile = getResourcePath(context).resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) return false;
        String hash;
        try {
            hash = computeSubjectHashOld(certFile);
        } catch (IOException | CertificateException | NoSuchAlgorithmException e) {
            Timber.w(e, "Failed to compute certificate hash.");
            return false;
        }
        // Standard AOSP location for KeyChain-installed user CA certs
        // since Android 4.0 (ICS) - readable directly with root, same
        // approach as isSystemCertificateInstalled() above.
        return Shell.cmd("test -f /data/misc/user/0/cacerts-added/" + hash + ".0")
                    .exec().isSuccess();
    }

    public static boolean isSystemCertificateInstalled(Context context) {
        Path certFile = getResourcePath(context).resolve(CA_CERT_FILE);
        if (!Files.isRegularFile(certFile)) return false;
        String hash;
        try {
            hash = computeSubjectHashOld(certFile);
        } catch (IOException | CertificateException | NoSuchAlgorithmException e) {
            Timber.w(e, "Failed to compute certificate hash.");
            return false;
        }
        return Shell.cmd("test -f /system/etc/security/cacerts/" + hash + ".0")
                    .exec().isSuccess();
    }

    /**
     * Compute OpenSSL's "subject_hash_old" for a certificate - the legacy
     * c_rehash filename convention ("<hash>.0") the Android system trust
     * store still uses under /system/etc/security/cacerts. It's the first
     * 4 bytes of MD5(DER-encoded subject name), read as a little-endian
     * unsigned 32-bit integer and printed as 8 lowercase hex digits.
     * Reimplemented here in pure Java so this app never depends on an
     * `openssl` CLI binary actually existing on the device.
     */
    private static String computeSubjectHashOld(Path certFile)
            throws IOException, CertificateException, NoSuchAlgorithmException {
        X509Certificate cert;
        try (InputStream is = Files.newInputStream(certFile)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is);
        }
        byte[] subjectDer = cert.getSubjectX500Principal().getEncoded();
        byte[] digest = MessageDigest.getInstance("MD5").digest(subjectDer);
        long hash = (digest[0] & 0xFFL)
                | ((digest[1] & 0xFFL) << 8)
                | ((digest[2] & 0xFFL) << 16)
                | ((digest[3] & 0xFFL) << 24);
        return String.format("%08x", hash);
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
}
