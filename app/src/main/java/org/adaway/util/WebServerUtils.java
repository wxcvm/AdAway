package org.adaway.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
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
    /*
     * Use the IPv4-mapped loopback ([::ffff:127.0.0.1]) instead of a
     * plain 127.0.0.1 connection: on this device's kernel the loopback
     * uid column in /proc/net/tcp is zeroed for plain IPv4 / IPv6
     * connections, but preserved for v4-mapped sockets — which is what
     * per-app statistics rely on to identify the requesting app.
     */
    public static final String STATS_URL = "http://[::ffff:127.0.0.1]/internal-stats";
    private static final String WEB_SERVER_EXECUTABLE = "webserver";
    private static final String CA_CERT_FILE = "localhost-2410.crt";
    private static final String CA_KEY_FILE  = "localhost-2410.key";
    private static final String PREFS_WS = "compose_webserver";

    /** Whether the web server listens on all interfaces (LAN). Default false. */
    public static boolean isBindAll(Context context) {
        return context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE)
                .getBoolean("bind_all", false);
    }

    /** Configured HTTP listen port (default 80). */
    public static int getHttpPort(Context context) {
        return context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE)
                .getInt("http_port", 80);
    }

    /** Configured HTTPS listen port (default 443). */
    public static int getHttpsPort(Context context) {
        return context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE)
                .getInt("https_port", 443);
    }

    public static void setWebServerSettings(Context context, boolean bindAll, int httpPort, int httpsPort) {
        context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("bind_all", bindAll)
                .putInt("http_port", httpPort)
                .putInt("https_port", httpsPort)
                .apply();
        sHttpPortCached = httpPort;
        sHttpsPortCached = httpsPort;
    }

    private static volatile int sHttpPortCached = 80;
    private static volatile int sHttpsPortCached = 443;

    /** Load persisted ports into the static cache (called at app start). */
    public static void initPortCache(Context context) {
        sHttpPortCached = context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE)
                .getInt("http_port", 80);
        sHttpsPortCached = context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE)
                .getInt("https_port", 443);
    }

    /** Port used by the stats client (cached; no Context needed). */
    public static int getStatsHttpPort() {
        return sHttpPortCached;
    }

    /** Test page URL honoring the configured HTTPS port. */
    public static String getTestUrl(Context context) {
        int port = getHttpsPort(context);
        return port == 443 ? "https://localhost/internal-test" :
                "https://localhost:" + port + "/internal-test";
    }
/**
     * Fetch the web server's statistics snapshot (uptime, blocked
     * request counts broken down by type, SNI certs issued, per-app
     * breakdown) as a parsed JSONObject. Returns null when the server
     * is not running or the response is unparseable.
     * <p>
     * IMPORTANT: the request must come from an IPv4-mapped IPv6 socket
     * ([::ffff:127.0.0.1]). On this device's kernel, plain IPv4/IPv6
     * loopback connections show uid 0 in /proc/net/tcp, so the web
     * server could never tell which app is asking; v4-mapped sockets
     * keep the real uid. Java (OkHttp and even raw Socket with a
     * v4-mapped Inet6Address) collapses the address back to IPv4, so
     * we spawn toybox nc instead — the native child keeps the app uid
     * and connects with a genuine v4-mapped socket (verified: uid is
     * preserved in /proc/net/tcp6).
     */
    @androidx.annotation.Nullable
    /**
     * 拉取 webserver 统计快照（/internal-stats）并解析为 JSON。
     *
     * 实现要点（历史踩坑记录）：
     *  1. 必须使用 toybox nc 子进程而非 Java Socket：
     *     Java 会把 v4-mapped 地址折叠回纯 IPv4，导致 /proc/net/tcp
     *     中 uid 被 ColorOS 内核清零，per-app 统计失效。
     *  2. 目标地址为 ::ffff:127.0.0.1（v4-mapped），子进程继承
     *     app uid，内核在 tcp6 表中保留真实 uid。
     *  3. 响应体可能超过 4KB（history+daily+apps），使用 ByteArrayOutputStream
     *     累积读取，避免截断导致 JSON 解析失败。
     *
     * @return 解析后的 JSONObject；webserver 未运行或响应不可解析时返回 null。
     */
public static org.json.JSONObject getStats() {
        try {
            Process process = new ProcessBuilder(
                    "/system/bin/toybox", "nc", "-w", "3",
                    "::ffff:127.0.0.1", String.valueOf(getStatsHttpPort()))
                    .redirectErrorStream(false)
                    .start();
            java.io.OutputStream out = process.getOutputStream();
            out.write(("GET /internal-stats HTTP/1.1\r\n" +
                    "Host: adaway\r\n" +
                    "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.close();
            java.io.InputStream in = process.getInputStream();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
            in.close();
            if (!process.waitFor(4000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
                return null;
            }
            String response = body.toString("UTF-8");
            int headerEnd = response.indexOf("\r\n\r\n");
            String json = headerEnd >= 0 ? response.substring(headerEnd + 4) : response;
            return new org.json.JSONObject(json);
        } catch (Exception e) {
            Timber.w(e, "Failed to fetch web server stats (nc)");
            return null;
        }
    }

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
    /**
     * 启动 webserver 守护进程（libwebserver_exec.so）。
     *
     * 流程：
     *  1. ensureStaticResources() 生成/更新 CA 证书与拦截占位图；
     *  2. 检查原生库可执行文件存在且可读；
     *  3. 杀掉残留旧进程，等待端口释放；
     *  4. 以 root 身份（ShellUtils.runBundledExecutable）启动，
     *     传入 --bind/--http-port/--https-port 参数（来自偏好）。
     *
     * 注意：所有 Toast 必须通过 showToast() 切到主线程，
     * 否则在后台线程调用会抛 CalledFromWrongThreadException。
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

        String params = "--resources " + resourcePath.toAbsolutePath() +
                " --debug --bind " + (isBindAll(context) ? "all" : "loop") +
                " --http-port " + getHttpPort(context) +
                " --https-port " + getHttpsPort(context);
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
    /**
     * 计算 webserver 状态字符串资源 ID。
     *
     * 判定顺序：
     *  1. 进程未运行 → not_running
     *  2. HTTP 探活失败 → not_running
     *  3. 证书哈希与上次记录不一致（cert_hash 偏好）→ cert_expired
     *     （证书被重新生成，用户需重新安装 CA）
     *  4. 系统信任库存在 → installed_system（所有应用信任）
     *  5. 用户信任库存在 → installed（仅用户级）
     *  6. 否则 → not_installed
     *
     * 说明：证书哈希比较是纯文件级检测（MD5(subject DER)），
     * 不依赖 OS TLS 握手，避免网络安全配置差异导致的误报。
     */
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

        // Check if certificate has changed since last check
        String currentHash = computeCertHash(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WS, Context.MODE_PRIVATE);
        String storedHash = prefs.getString("cert_hash", null);
        
        if (currentHash != null && storedHash != null && !currentHash.equals(storedHash)) {
            // Certificate has changed — cache is stale, need reinstall
            return R.string.pref_webserver_state_running_cert_expired;
        }
        // Store current hash for next comparison
        if (currentHash != null) {
            prefs.edit().putString("cert_hash", currentHash).apply();
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
         * caching as the explanation). Don't depend on live OS-level TLS
         * trust resolution for a yes/no answer we can determine more
         * directly: check whether a file matching our CA's hash exists in
         * either the system or user trust-anchor directory.
         *
         * Report system vs. user cert separately (rather than a single
         * merged "installed" boolean) so the status line actually answers
         * whether the cert ended up trusted at the system level or only
         * the user level — the two have meaningfully different trust
         * scope (system certs are honored by apps that ignore user-added
         * ones), and this can change without any action inside AdAway
         * itself, e.g. a Magisk "move certificates"-style module
         * promoting an already-installed user cert to system on the next
         * boot.
         */
        if (isSystemCertificateInstalled(context)) {
            return R.string.pref_webserver_state_running_and_installed_system;
        }
        return isUserCertificateInstalled(context)
                ? R.string.pref_webserver_state_running_and_installed
                : R.string.pref_webserver_state_running_not_installed;
    }

    /** Compute MD5-based subject hash of the CA certificate file. Returns null on error. */
    private static String computeCertHash(Context context) {
        try {
            Path certFile = getResourcePath(context).resolve(CA_CERT_FILE);
            if (!Files.isRegularFile(certFile)) return null;
            java.security.cert.CertificateFactory cf = 
                java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate cert;
            try (java.io.InputStream is = Files.newInputStream(certFile)) {
                cert = (java.security.cert.X509Certificate) cf.generateCertificate(is);
            }
            byte[] subjectDer = cert.getSubjectX500Principal().getEncoded();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(subjectDer);
            long hash = ((long)(digest[0] & 0xFF)) 
                      | ((digest[1] & 0xFFL) << 8) 
                      | ((digest[2] & 0xFFL) << 16) 
                      | ((digest[3] & 0xFFL) << 24);
            return String.format("%08x", hash);
        } catch (Exception e) {
            Timber.w(e, "Failed to compute certificate hash.");
            return null;
        }
    }

    /**
     * Install AdAway's CA into the user trust store (KeyChain).
     * Works on all Android versions but requires the user to confirm.
     */
    /**
     * 引导用户安装 CA 证书（KeyChain 系统弹窗）。
     *
     * Android 11+ 禁止应用静默安装 CA，必须由用户在
     * “设置 → 安全 → 加密与凭据 → 安装证书”中手动确认。
     * 这里构造 KeyChain.createInstallIntent() 并附带证书字节。
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
            intent.putExtra(android.security.KeyChain.EXTRA_NAME, "ADBlock CA");
            context.startActivity(intent);
        } catch (IOException | CertificateException e) {
            Timber.w(e, "Failed to prepare certificate for install.");
        }
    }

    /**
     * Check whether AdAway's CA is installed as a *user* trust anchor by
     * looking directly at the on-disk file Android's KeyChain writes
     * user-installed CA certs to, rather than performing a live HTTPS
     * handshake and hoping network_security_config's per-domain trust
     * override kicks in. See the comment in getWebServerState() for why.
     */
    /**
     * 检查 AdAway CA 是否已安装为“用户”信任锚。
     *
     * 直接检查 KeyChain 写入的证书文件
     * /data/misc/user/0/cacerts-added/<subject_hash_old>.0 是否存在
     * （需要 root 读取），文件名哈希与 OpenSSL c_rehash 一致。
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

    /**
     * Check whether AdAway's CA is already present in the system trust store
     * - e.g. via a Magisk "move certificates"-style module promoting an
     * already-installed user cert, since this app no longer attempts a
     * direct root-based system install itself.
     * <p>
     * BUG FIX: this used to check only /system/etc/security/cacerts.
     * Confirmed on-device report: a cert correctly moved to the system
     * store by an external tool still showed as "user" here. Reason:
     * starting Android 14 (and some earlier devices that already received
     * a Conscrypt Mainline module update), the trust store apps actually
     * consult at runtime moved to /apex/com.android.conscrypt/cacerts, a
     * read-only APEX container - /system/etc/security/cacerts is no
     * longer read at all on those devices, even though it still physically
     * exists and even root can still write to it. This is a well-documented
     * platform change (see e.g. httptoolkit.com/blog/android-14-breaks-
     * system-certificate-installation), and it's exactly why this app
     * removed its own root-based *install* attempt earlier - properly
     * injecting into the APEX path requires bind-mounting into every app's
     * Zygote-inherited mount namespace, well beyond a one-shot root shell
     * command. Detection is simpler: just check whichever path this
     * device actually has. Prefer the APEX path when it exists (it's the
     * one that matters on any device that has it), falling back to the
     * legacy /system path for older devices that never had a conscrypt
     * APEX module at all.
     */
    /**
     * 检查 CA 是否已进入“系统”信任库。
     *
     * Android 14+ 实际读取的是 /apex/com.android.conscrypt/cacerts
     * （只读 APEX），旧设备仍是 /system/etc/security/cacerts；
     * 优先检测 APEX 路径，不存在时回退旧路径。
     */
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
        boolean hasApexStore = Shell.cmd("test -d /apex/com.android.conscrypt/cacerts")
                                     .exec().isSuccess();
        String certsDir = hasApexStore
                ? "/apex/com.android.conscrypt/cacerts/"
                : "/system/etc/security/cacerts/";
        return Shell.cmd("test -f " + certsDir + hash + ".0").exec().isSuccess();
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
    /**
     * 计算 OpenSSL subject_hash_old（c_rehash 文件名哈希）。
     *
     * 算法：MD5(DER 编码的 subject DN)，取前 4 字节按
     * 小端序拼成 32 位整数，格式化为 8 位十六进制。
     * 纯 Java 实现，避免依赖设备上的 openssl 二进制。
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
            /*
             * BUG FIX (usability): this used to require contiguous,
             * zero-padded numbering (img_00, img_01, ...) - deleting one
             * image meant renaming every image after it to close the gap.
             * Scan all assets for anything actually matching the block-
             * image naming pattern instead (any suffix, e.g. img_00.png,
             * img_cat.jpg, img_2024-ad.webp all qualify) and inflate
             * whatever's found - deleting one doesn't touch any other
             * file's name. Matches the equivalent scan_block_images() on
             * the native side.
             *
             * BUG FIX (stale defaults): the old code only inflated a
             * given image when its output file was missing, so updating
             * the bundled assets in a new APK never replaced the images
             * already inflated on a device (overlay install keeps
             * files/webserver). Detect asset changes via a content hash
             * of all bundled block images stored in a version file, and
             * re-inflate (i.e. reset to the new defaults) only when the
             * APK's assets actually differ - user-customized images are
             * untouched as long as the APK itself didn't change.
             */
            String[] assetNames = am.list("");
            if (assetNames != null) {
                String assetsHash = computeBlockImageAssetsHash(am, assetNames);
                Path versionFile = target.resolve(BLOCK_IMAGES_VERSION_FILE);
                String storedHash = Files.isRegularFile(versionFile)
                        ? new String(Files.readAllBytes(versionFile), java.nio.charset.StandardCharsets.UTF_8)
                        : "";
                boolean assetsChanged = !assetsHash.equals(storedHash);
                boolean noInflatedSlots = listBlockImageSlots(target).isEmpty();
                if (assetsChanged || noInflatedSlots) {
                    // Clear any previously inflated (or user-customized)
                    // images so the new defaults are extracted below.
                    for (Path slot : listBlockImageSlots(target)) {
                        Files.deleteIfExists(slot);
                    }
                    for (String assetName : assetNames) {
                        if (BLOCK_IMAGE_ASSET_PATTERN.matcher(assetName).matches()) {
                            inflateBlockImageAsset(am, assetName, target);
                        }
                    }
                    Files.write(versionFile,
                            assetsHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            Timber.w(e, "Failed to inflate web server resources.");
        }
        // Delete stale cert (missing SAN / EKU) so server regenerates on next start
        deleteStaleServerCert(target);
    }

    /**
     * Name of the marker file holding the content hash of the bundled
     * block-image assets. Used to detect APK upgrades that changed the
     * default images so they get re-inflated (see ensureStaticResources()).
     */
    private static final String BLOCK_IMAGES_VERSION_FILE = "block_images.version";

    /**
     * Compute a content hash over every bundled block-image asset (file
     * name + bytes). Any change to the image set - new images, edited
     * bytes, renamed/deleted files - produces a different hash, which is
     * how the app knows the defaults changed in a new APK.
     */
    private static String computeBlockImageAssetsHash(
            android.content.res.AssetManager am, String[] assetNames) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        for (String assetName : assetNames) {
            if (!BLOCK_IMAGE_ASSET_PATTERN.matcher(assetName).matches()) continue;
            md.update(assetName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (InputStream in = am.open(assetName)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Matches any block-placeholder source asset: "img_" + anything +
     * one of the accepted image extensions. No numbering/padding
     * requirement - "img_00.png", "img_cat.jpg", "img_2024-ad.webp" all
     * match.
     */
    private static final java.util.regex.Pattern BLOCK_IMAGE_ASSET_PATTERN =
            java.util.regex.Pattern.compile("^img_.+\\.(?:webp|png|jpe?g)$");

    /**
     * Inflate a single block-placeholder source asset (e.g. "img_00.png",
     * "img_cat.jpg") into the resource directory as "<base name>.webp" -
     * copied as-is if it's already WebP (fast path, byte-for-byte),
     * otherwise decoded and re-encoded once via the same
     * decode-then-Bitmap.CompressFormat.WEBP path already used by the
     * in-app runtime picker (setCustomBlockImage()). Skipped if that
     * output file already exists - ensureStaticResources() only needs to
     * do this once per image, not on every server start.
     */
    private static void inflateBlockImageAsset(android.content.res.AssetManager am, String assetName, Path target)
            throws IOException {
        String baseName = assetName.substring(0, assetName.lastIndexOf('.'));
        Path out = target.resolve(baseName + ".webp");
        if (Files.isRegularFile(out)) return; // already inflated

        try (InputStream in = am.open(assetName)) {
            if (assetName.endsWith(".webp")) {
                Files.copy(in, out);
            } else {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) {
                    Timber.w("Couldn't decode %s as an image, skipping.", assetName);
                    return;
                }
                try (OutputStream os = Files.newOutputStream(out)) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 85, os);
                } finally {
                    bitmap.recycle();
                }
            }
        }
    }

    /**
     * List the currently-inflated block-placeholder images in a resource
     * directory (files matching "img_*.webp", whatever their exact
     * names). Shared by setCustomBlockImage()/resetBlockImagesToDefault()
     * so neither hardcodes a numbering scheme independently of what
     * ensureStaticResources()/scan_block_images() (native side) actually
     * use.
     */
    private static java.util.List<Path> listBlockImageSlots(Path resourceDir) {
        java.util.List<Path> result = new java.util.ArrayList<>();
        if (!Files.isDirectory(resourceDir)) return result;
        try (java.util.stream.Stream<Path> stream = Files.list(resourceDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("img_") && name.endsWith(".webp");
            }).forEach(result::add);
        } catch (IOException e) {
            Timber.w(e, "Failed to list block image slots.");
        }
        return result;
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

    /**
     * Replace all 7 block-placeholder images (img_00.webp .. img_06.webp,
     * served by the native web server in place of blocked ads) with a
     * single image the user picked.
     * <p>
     * Decodes whatever format was picked (JPEG/PNG/etc.) and re-encodes it
     * as WEBP before writing, since that's the only format the native
     * server's MIME-type mapping (and file extension it looks for) knows
     * about. Every configured slot gets the same image - the server
     * picks one at random per request purely for visual variety, not
     * because they're meant to differ in content.
     *
     * @return true if the image was decoded and written successfully.
     */
    public static boolean setCustomBlockImage(Context context, Uri imageUri) {
        Bitmap bitmap;
        try (InputStream is = context.getContentResolver().openInputStream(imageUri)) {
            if (is == null) return false;
            bitmap = BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Timber.e(e, "Failed to read picked block image.");
            return false;
        }
        if (bitmap == null) {
            Timber.e("Failed to decode picked block image.");
            return false;
        }
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP;
        Path resourceDir = getResourcePath(context);
        try {
            if (!Files.isDirectory(resourceDir)) Files.createDirectories(resourceDir);
            java.util.List<Path> slots = listBlockImageSlots(resourceDir);
            if (slots.isEmpty()) slots = java.util.Collections.singletonList(resourceDir.resolve("img_00.webp"));
            for (Path out : slots) {
                try (OutputStream os = Files.newOutputStream(out)) {
                    bitmap.compress(format, 90, os);
                }
            }
            return true;
        } catch (IOException e) {
            Timber.e(e, "Failed to write custom block image.");
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * Delete the (possibly user-customized) block-placeholder images so
     * ensureStaticResources() re-extracts the original defaults from the
     * APK's assets on the next web server start - it only ever copies a
     * given filename if it's missing, which is what makes both this and
     * setCustomBlockImage() above work without needing any extra "is this
     * customized" flag.
     */
    public static void resetBlockImagesToDefault(Context context) {
        Path resourceDir = getResourcePath(context);
        for (Path slot : listBlockImageSlots(resourceDir)) {
            try {
                Files.deleteIfExists(slot);
            } catch (IOException e) {
                Timber.w(e, "Failed to delete block image %s for reset.", slot);
            }
        }
        ensureStaticResources(context, resourceDir);
    }

    private static void inflateResource(android.content.res.AssetManager am,
                                        String name, Path target) throws IOException {
        Path out = target.resolve(name);
        if (!Files.isRegularFile(out)) Files.copy(am.open(name), out);
    }
}
