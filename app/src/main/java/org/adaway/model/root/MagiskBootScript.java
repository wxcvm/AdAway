package org.adaway.model.root;

import android.content.Context;
import android.util.Base64;

import com.topjohnwu.superuser.Shell;

import org.adaway.util.BlockMode;
import org.adaway.util.WebServerUtils;

import java.nio.charset.StandardCharsets;

import timber.log.Timber;

/**
 * Init-side start script for devices rooted with Magisk (or KernelSU/APatch,
 * which execute {@code /data/adb/service.d/*.sh} the same way).
 *
 * <p>The regular path (BOOT_COMPLETED → {@code BootReceiver} → WorkManager) needs
 * the app process to be woken up at boot. Several OEM ROMs delay or drop that
 * broadcast and defer the queued work until the app is opened, and
 * force-stopping the app cancels it entirely — which is what "the server only
 * starts a few seconds after I open the app" looks like. With root, this script
 * is executed from the init side, so no app process and no broadcast is
 * involved at all.</p>
 *
 * <p>The command line is kept <b>byte-for-byte compatible</b> with
 * {@link WebServerUtils#startWebServer(Context)}: same resources, bind mode,
 * ports and {@code --stats-port}, plus {@code --proxy-filter} in hijack mode.
 * A boot-started server that missed {@code --stats-port} would answer on a
 * different management port (the app would think it is not running and start a
 * second one), and one that missed {@code --proxy-filter} in hijack mode would
 * forward every non-blocked host unfiltered.</p>
 */
public final class MagiskBootScript {

    /** Directory the script lives in (Magisk runs every *.sh in it). */
    private static final String SERVICE_D_DIR = "/data/adb/service.d";
    private static final String SCRIPT_PATH = SERVICE_D_DIR + "/adblock-webserver.sh";

    /** Where the script writes what it did (readable over adb/a file manager). */
    public static final String LOG_PATH = "/data/local/tmp/adblock-boot.log";

    /** How long {@link #runNow(Context)} waits for the server to answer. */
    private static final long RUN_NOW_VERIFY_MS = 20_000L;

    private MagiskBootScript() {
    }

    /**
     * @return {@code true} when this device has a root solution that owns
     *         {@code /data/adb} (Magisk, KernelSU, APatch all do).
     *
     *         <p>This used to require {@code /data/adb/service.d} to already
     *         exist, which was wrong: KernelSU creates that directory late in
     *         boot, so on the boot where the app first ran the probe answered
     *         "unsupported" and no script was written - it only appeared after
     *         the next app start (measured on-device: directory created at
     *         12:37, app process started 12:35 and again 12:40). {@link
     *         #install(Context)} creates the directory itself with
     *         {@code mkdir -p}, so probing {@code /data/adb} is both sufficient
     *         and race-free.</p>
     */
    public static boolean isSupported() {
        Shell.Result result = Shell.cmd("[ -d /data/adb ]").exec();
        return result.isSuccess();
    }

    /**
     * @return {@code true} when our script is currently installed.
     */
    public static boolean isInstalled() {
        Shell.Result result = Shell.cmd("[ -f " + SCRIPT_PATH + " ]").exec();
        return result.isSuccess();
    }

    /**
     * @return The script path, for diagnostics.
     */
    public static String scriptPath() {
        return SCRIPT_PATH;
    }

    /**
     * Write (or refresh) the boot script from the current settings.
     *
     * <p>Safe to call on every start: paths, bind mode and ports are rewritten,
     * so an app update or a settings change cannot leave a stale script behind.
     * The result is verified by reading the file back.</p>
     *
     * @param context application context (native library + resource paths).
     * @return {@code true} when the script is in place and matches the settings.
     */
    public static boolean install(Context context) {
        if (!isSupported()) {
            Timber.i("MagiskBootScript: no %s on this device - the app-side paths stay the only ones",
                    SERVICE_D_DIR);
            return false;
        }
        boolean bindAll = WebServerUtils.isBindAll(context);
        int httpPort = WebServerUtils.getHttpPort(context);
        int httpsPort = WebServerUtils.getHttpsPort(context);
        boolean proxyFilter = BlockMode.current(context) == BlockMode.HIJACK;
        /*
         * In hijack mode the boot-started server reads <resources>/blocklist.txt
         * once at startup and forwards every host missing from it: a stale
         * export therefore means "ads load again" until the app is opened. Write
         * a fresh one before the script can be executed at boot.
         */
        if (proxyFilter) {
            WebServerUtils.exportBlockList(context);
        }
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        String resourceDir = WebServerUtils.getResourcePath(context).toAbsolutePath().toString();
        String script = buildScript(nativeLibDir, resourceDir, bindAll, httpPort, httpsPort,
                WebServerUtils.STATS_PORT, proxyFilter);
        // base64 keeps the shell command free of any quoting/escaping of paths.
        String encoded = Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String cmd = "mkdir -p " + SERVICE_D_DIR
                + " && echo " + encoded + " | base64 -d > " + SCRIPT_PATH
                + " && chmod 755 " + SCRIPT_PATH
                + " && [ -s " + SCRIPT_PATH + " ]";
        Shell.Result result = Shell.cmd(cmd).exec();
        if (!result.isSuccess()) {
            Timber.w("MagiskBootScript: could not write %s: %s", SCRIPT_PATH,
                    ShellUtils.mergeAllLines(result.getErr()));
            return false;
        }
        /*
         * Verify instead of trusting the write: a script that lost --stats-port
         * (wrong management port) or --proxy-filter (unfiltered forwarding in
         * hijack mode) is worse than no script at all, because it looks fine.
         */
        String onDisk = readScript();
        if (!onDisk.contains("--http-port " + httpPort)
                || !onDisk.contains("--https-port " + httpsPort)
                || !onDisk.contains("--stats-port " + WebServerUtils.STATS_PORT)
                || onDisk.contains("--proxy-filter") != proxyFilter) {
            Timber.w("MagiskBootScript: verification failed for %s", SCRIPT_PATH);
            return false;
        }
        Timber.i("MagiskBootScript: installed and verified %s", SCRIPT_PATH);
        return true;
    }

    /**
     * Remove the boot script (called when "start at boot" is switched off).
     *
     * @return {@code true} when the script is gone afterwards.
     */
    public static boolean uninstall() {
        Shell.Result result = Shell.cmd("rm -f " + SCRIPT_PATH).exec();
        boolean ok = result.isSuccess() && !isInstalled();
        Timber.i("MagiskBootScript: uninstall %s", ok ? "ok" : "failed");
        return ok;
    }

    /**
     * Read the installed script (empty when it cannot be read).
     *
     * @return The script content, newline separated.
     */
    public static String readScript() {
        Shell.Result result = Shell.cmd("cat " + SCRIPT_PATH + " 2>/dev/null").exec();
        return String.join("\n", result.getOut());
    }

    /**
     * Run the installed script right now and report whether the server came up.
     *
     * <p>This is the on-device proof that the init-side path works: the script
     * itself is exactly what Magisk runs at boot, so success here means "the
     * mechanism is able to start the server", and a failure points at the script
     * log instead of leaving the user to guess.</p>
     *
     * @param context application context (only used to probe reachability).
     * @return A short, user-displayable outcome.
     */
    public static String runNow(Context context) {
        if (!isInstalled()) {
            return "开机脚本未安装（本机没有 " + SERVICE_D_DIR + "，或未开启开机自动启动）";
        }
        Shell.Result result = Shell.cmd("sh " + SCRIPT_PATH).exec();
        if (!result.isSuccess()) {
            String err = ShellUtils.mergeAllLines(result.getErr());
            return "脚本执行失败：" + (err.isEmpty() ? "退出码 " + result.getCode() : err);
        }
        long deadline = System.currentTimeMillis() + RUN_NOW_VERIFY_MS;
        while (System.currentTimeMillis() < deadline) {
            if (WebServerUtils.isWebServerReachable(context)) {
                return "脚本运行成功：服务器已启动";
            }
            sleep(500L);
        }
        return "脚本已执行，但服务器未在 20 秒内就绪。开机日志：\n" + readLogTail();
    }

    /**
     * @return The last lines of the script's own log, or an explanation why it
     *         is empty.
     */
    public static String readLogTail() {
        Shell.Result result = Shell.cmd("tail -n 20 " + LOG_PATH + " 2>/dev/null").exec();
        String out = String.join("\n", result.getOut()).trim();
        if (!out.isEmpty()) {
            return out;
        }
        return "（暂无日志：脚本还没有在开机时执行过。重启一次后再看这里。）";
    }

    /**
     * The generated script. Deliberately small: wait for the boot to finish,
     * make sure the staged binary is there, start it in the background, exit.
     * Nothing here may block the boot, and nothing loops forever.
     *
     * @param nativeLibDir the app's native library directory (for LD_LIBRARY_PATH).
     * @param resourceDir  the server's resource directory.
     * @param bindAll      listen on all interfaces instead of loopback only.
     * @param httpPort     HTTP port.
     * @param httpsPort    HTTPS port.
     * @param statsPort    management port (must match the app, see the class doc).
     * @param proxyFilter  add {@code --proxy-filter} (hijack mode only).
     * @return The script content.
     */
    static String buildScript(String nativeLibDir, String resourceDir,
                              boolean bindAll, int httpPort, int httpsPort,
                              int statsPort, boolean proxyFilter) {
        String libName = "lib" + WebServerUtils.WEB_SERVER_EXECUTABLE + "_exec.so";
        String bin = "/data/local/tmp/" + libName;
        String src = nativeLibDir + "/" + libName;
        return "#!/system/bin/sh\n"
                + "# Written by ADBlock. Starts the native intercept server at boot\n"
                + "# without depending on the app process or on BOOT_COMPLETED.\n"
                + "# Deleted automatically when \"start at boot\" is switched off.\n"
                + "LOG=" + LOG_PATH + "\n"
                + "BIN=" + bin + "\n"
                + "APP_LIB=" + nativeLibDir + "\n"
                + "RES=" + resourceDir + "\n"
                + "(\n"
                + "  i=0\n"
                + "  while [ \"$(getprop sys.boot_completed)\" != \"1\" ] && [ $i -lt 150 ]; do\n"
                + "    sleep 2; i=$((i+1))\n"
                + "  done\n"
                + "  # service.d runs at late_start, which on an encrypted device is\n"
                + "  # normally still BEFORE the first unlock: /data/user/0/<pkg> lives\n"
                + "  # on credential-encrypted storage and is not visible yet, so a missing\n"
                + "  # $RES says nothing about the app being installed. Every boot until\n"
                + "  # now logged 'app data gone' and deleted this script for that reason,\n"
                + "  # which is why the server never started from the init side.\n"
                + "  u=0\n"
                + "  while [ ! -d \"$RES\" ] && [ \"$(getprop sys.user.0.ce_available)\" != \"true\" ] && [ $u -lt 300 ]; do\n"
                + "    sleep 2; u=$((u+1))\n"
                + "  done\n"
                + "  if [ ! -d \"$RES\" ]; then\n"
                + "    if [ \"$(getprop sys.user.0.ce_available)\" != \"true\" ]; then\n"
                + "      echo \"$(date) user storage still locked - keeping this boot script\" >> \"$LOG\"\n"
                + "      exit 0\n"
                + "    fi\n"
                + "    PKG=$(echo \"$RES\" | cut -d/ -f5)\n"
                + "    if [ -n \"$PKG\" ] && pm path \"$PKG\" >/dev/null 2>&1; then\n"
                + "      echo \"$(date) app installed, its data dir is not ready - keeping this boot script\" >> \"$LOG\"\n"
                + "      exit 0\n"
                + "    fi\n"
                + "    MISS=/data/local/tmp/adblock-boot-miss\n"
                + "    n=0; [ -f \"$MISS\" ] && n=$(cat \"$MISS\")\n"
                + "    n=$((n+1)); echo $n > \"$MISS\"\n"
                + "    if [ $n -ge 2 ]; then\n"
                + "      echo \"$(date) app really gone ($n boots) - removing this boot script\" >> \"$LOG\"\n"
                + "      rm -f " + SCRIPT_PATH + " \"$MISS\"\n"
                + "      exit 0\n"
                + "    fi\n"
                + "    echo \"$(date) app data missing, boot $n of 2 - keeping this boot script\" >> \"$LOG\"\n"
                + "    exit 0\n"
                + "  fi\n"
                + "  rm -f /data/local/tmp/adblock-boot-miss\n"
                + "  if [ ! -x \"$BIN\" ] && [ -f \"" + src + "\" ]; then\n"
                + "    cp -f \"" + src + "\" \"$BIN\" && chmod 755 \"$BIN\"\n"
                + "  fi\n"
                + "  echo \"$(date) boot start\" >> \"$LOG\"\n"
                + "  LD_LIBRARY_PATH=\"$APP_LIB\" \"$BIN\" --resources \"$RES\""
                + " --bind " + (bindAll ? "all" : "loop")
                + " --stats-port " + statsPort
                + " --http-port " + httpPort
                + " --https-port " + httpsPort
                + (proxyFilter ? " --proxy-filter" : "")
                + " >> \"$LOG\" 2>&1 &\n"
                + ") &\n"
                + "exit 0\n";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
