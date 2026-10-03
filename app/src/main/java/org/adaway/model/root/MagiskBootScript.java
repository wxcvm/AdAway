package org.adaway.model.root;

import android.content.Context;
import android.util.Base64;

import com.topjohnwu.superuser.Shell;

import org.adaway.util.WebServerUtils;

import java.nio.charset.StandardCharsets;

import timber.log.Timber;

/**
 * Init-side start script for devices rooted with Magisk.
 *
 * <p>The regular path (BOOT_COMPLETED → {@code BootReceiver} → WorkManager) needs
 * the app process to be woken up at boot. Several OEM ROMs (MIUI/HyperOS,
 * EMUI, ColorOS, …) delay or drop that broadcast, and force-stopping the app
 * cancels the queued work entirely — which is what "the server is not running
 * until I open the app" looks like. Tools like Scene avoid the problem by not
 * going through the app at all: with root, {@code /data/adb/service.d/*.sh} is
 * executed by Magisk right after boot, so the native server can be started from
 * the init side.</p>
 *
 * <p>What this class does:</p>
 * <ul>
 *     <li>writes {@code /data/adb/service.d/adblock-webserver.sh} as root, using
 *     {@code base64 -d} so no quoting of the recorded paths can go wrong;</li>
 *     <li>the script waits (bounded, at most ~5 min) for
 *     {@code sys.boot_completed}, makes sure the staged binary exists, starts it
 *     in the background and exits — it never blocks the boot and never loops
 *     forever;</li>
 *     <li>removes the script again when the user switches "start at boot"
 *     off.</li>
 * </ul>
 *
 * <p>The command line mirrors what {@code WebServerUtils.startWebServer()} uses
 * (resources + bind + ports, no {@code --stats-port}, so the server keeps its
 * default management port) — both paths must look identical to the
 * single-instance detection.</p>
 *
 * <p>The native server already detects an instance listening on the management
 * port and exits instead of binding twice, so this script racing the normal
 * boot-receiver path is harmless: exactly one server survives.</p>
 */
public final class MagiskBootScript {

    /** Directory the script lives in (Magisk runs every *.sh in it). */
    private static final String SERVICE_D_DIR = "/data/adb/service.d";
    private static final String SCRIPT_PATH = SERVICE_D_DIR + "/adblock-webserver.sh";

    /** Where the script writes what it did (readable over adb/a file manager). */
    private static final String LOG_PATH = "/data/local/tmp/adblock-boot.log";

    private MagiskBootScript() {
    }

    /**
     * @return {@code true} when this device runs Magisk service.d scripts (only
     *         Magisk creates that directory).
     */
    public static boolean isSupported() {
        Shell.Result result = Shell.cmd("[ -d " + SERVICE_D_DIR + " ]").exec();
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
     * Write (or refresh) the boot script. Safe to call on every enable: the
     * recorded paths, bind mode and ports are rewritten, so an app update or a
     * settings change cannot leave a stale script behind.
     *
     * @param context   application context (native library + resource paths).
     * @param bindAll   whether the server should listen on all interfaces.
     * @param httpPort  the HTTP port.
     * @param httpsPort the HTTPS port.
     * @return {@code true} when the script is in place afterwards.
     */
    public static boolean install(Context context, boolean bindAll, int httpPort, int httpsPort) {
        if (!isSupported()) {
            Timber.i("MagiskBootScript: no %s on this device - the broadcast path stays the only one",
                    SERVICE_D_DIR);
            return false;
        }
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        String resourceDir = WebServerUtils.getResourcePath(context).toAbsolutePath().toString();
        String script = buildScript(nativeLibDir, resourceDir, bindAll, httpPort, httpsPort);
        // base64 keeps the shell command free of any quoting/escaping of paths.
        String encoded = Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String cmd = "mkdir -p " + SERVICE_D_DIR
                + " && echo " + encoded + " | base64 -d > " + SCRIPT_PATH
                + " && chmod 755 " + SCRIPT_PATH
                + " && [ -s " + SCRIPT_PATH + " ]";
        Shell.Result result = Shell.cmd(cmd).exec();
        boolean ok = result.isSuccess();
        if (ok) {
            Timber.i("MagiskBootScript: installed %s", SCRIPT_PATH);
        } else {
            Timber.w("MagiskBootScript: could not write %s: %s", SCRIPT_PATH,
                    ShellUtils.mergeAllLines(result.getErr()));
        }
        return ok;
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
     * The generated script. Deliberately small: wait for the boot to finish,
     * make sure the staged binary exists, start it in the background, exit.
     * Nothing here may block the boot, and nothing loops forever.
     */
    static String buildScript(String nativeLibDir, String resourceDir,
                              boolean bindAll, int httpPort, int httpsPort) {
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
                + "  if [ ! -x \"$BIN\" ] && [ -f \"" + src + "\" ]; then\n"
                + "    cp -f \"" + src + "\" \"$BIN\" && chmod 755 \"$BIN\"\n"
                + "  fi\n"
                + "  echo \"$(date) boot start\" >> \"$LOG\"\n"
                + "  LD_LIBRARY_PATH=\"$APP_LIB\" \"$BIN\" --resources \"$RES\""
                + " --bind " + (bindAll ? "all" : "loop")
                + " --http-port " + httpPort
                + " --https-port " + httpsPort
                + " >> \"$LOG\" 2>&1 &\n"
                + ") &\n"
                + "exit 0\n";
    }
}
