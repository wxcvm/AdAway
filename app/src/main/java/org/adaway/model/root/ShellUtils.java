package org.adaway.model.root;

import static com.topjohnwu.superuser.ShellUtils.escapedString;

import android.content.Context;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.List;
import java.util.Optional;

import timber.log.Timber;

/**
 * This class is an utility class to help with shell commands.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class ShellUtils {
    private static final String EXECUTABLE_PREFIX = "lib";
    private static final String EXECUTABLE_SUFFIX = "_exec.so";

    /**
     * Private constructor.
     */
    private ShellUtils() {

    }

    public static String mergeAllLines(List<String> lines) {
        return String.join("\n", lines);
    }

    public static boolean isBundledExecutableRunning(String executable) {
        // Bracket trick: '[l]ib<exe>_exec.so' never matches its own command
        // line (the su -> sh -c wrapper would otherwise report a false
        // positive and isWebServerRunning() would be always true even when
        // the web server is dead). Strategy chain, any hit => true:
        //   1. pgrep -f            fast path (busybox/toybox)
        //   2. ps -A -o ARGS=      full cmdline (no 15-char comm truncation)
        //   3. grep /proc cmdline  most reliable last resort
        String name = EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX;
        String pattern = "[" + name.substring(0, 1) + "]" + name.substring(1);
        // 1) pgrep
        try {
            Shell.Result r = Shell.cmd("pgrep -f '" + pattern + "' >/dev/null 2>&1").exec();
            if (r.isSuccess()) return true;
        } catch (Exception ignored) {}
        // 2) ps with full ARGS column (avoids comm truncation at 15 chars)
        try {
            Shell.Result r = Shell.cmd("ps -A -o ARGS= 2>/dev/null | grep -E '" + pattern + "' >/dev/null 2>&1").exec();
            if (r.isSuccess()) return true;
        } catch (Exception ignored) {}
        // 3) /proc scan: match the executable name in any process cmdline
        try {
            Shell.Result r = Shell.cmd("grep -alE '" + pattern + "' /proc/[0-9]*/cmdline >/dev/null 2>&1").exec();
            if (r.isSuccess()) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * BUG FIX (root cause of the persistent "web server failed to start" /
     * empty-log-file reports): every previous version of this class
     * executed the bundled native binary directly out of the app's own
     * APK-mapped native library directory (getApplicationInfo().nativeLibraryDir,
     * e.g. /data/app/~~.../<pkg>-.../lib/arm64/). Manual on-device testing
     * confirmed that execve()-ing a file straight out of that location from
     * a root shell fails outright (before main() ever runs — no log output
     * of any kind, not even to logcat), while copying the exact same file
     * to /data/local/tmp and executing it from there works without any
     * other change. This matches a known Android/SELinux restriction on
     * treating an app-package-mapped path as an executable target for a
     * process outside the app's own (zygote-forked) domain, even when that
     * process is root.
     * <p>
     * Stage the executable into /data/local/tmp (same filename, so the
     * existing pgrep/ps pattern matching in isBundledExecutableRunning()/
     * killBundledExecutable() keeps working unmodified) before every run.
     * Shared libraries (libssl.so/libcrypto.so) are left in place and
     * still reached via LD_LIBRARY_PATH pointing at nativeLibraryDir.
     *
     * @return the staged, executable path, or {@code null} if staging failed.
     */
    private static String stageExecutable(Context context, String executable) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String srcPath = nativeLibraryDir + File.separator + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX;
        String stagedPath = "/data/local/tmp/" + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX;

        Shell.Result result = Shell.cmd(
                "cp -f " + escapedString(srcPath) + " " + escapedString(stagedPath) +
                        " && chmod 755 " + escapedString(stagedPath)
        ).exec();
        if (!result.isSuccess()) {
            Timber.e("Failed to stage %s to %s: %s", srcPath, stagedPath, mergeAllLines(result.getErr()));
            return null;
        }
        return stagedPath;
    }

    public static boolean runBundledExecutable(Context context, String executable, String parameters) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String binPath = stageExecutable(context, executable);
        if (binPath == null) {
            return false;
        }
        
        /*
         * BUG FIX (log lifecycle): every launch used to create a brand-new
         * timestamp-uniquified file (/data/local/tmp/webserver_start_<ms>.log),
         * and the success path below then unlinked it while the process still
         * held its stdout/stderr fd open on it. Three problems:
         *   1. Success-path unlink raced the running process — if it crashed
         *      later, its final output was already lost (fd open on a deleted
         *      file), defeating the very diagnostics this log exists for.
         *   2. The failure path left one orphan .log per launch behind in
         *      /data/local/tmp, accumulating unbounded files over every boot
         *      cycle / web-server toggle.
         *   3. The timestamp name made it awkward to inspect "the" current log.
         * Use ONE fixed path per executable ({executable}_start.log), truncate
         * it on launch (the > redirect already does), keep it after BOTH
         * success and failure so a later crash/start failure is always
         * recoverable, and neither accumulate files nor unlink a live fd.
         */
        String logPath = "/data/local/tmp/" + executable + "_start.log";

        // Start in background, redirect stdout/stderr to the (fixed) log file
        String cmd = "LD_LIBRARY_PATH=" + nativeLibraryDir + " " + binPath + " " + parameters +
                " > " + escapedString(logPath) + " 2>&1 &";
        
        Timber.d("Executing: %s", cmd);
        Shell.Result result = Shell.cmd(cmd).exec();
        
        if (!result.isSuccess()) {
            Timber.e("Launch command failed with exit code %d: %s", result.getCode(), mergeAllLines(result.getErr()));
            return false;
        }
        
        // Wait briefly for the process to appear
        for (int i = 0; i < 10; i++) {
            if (isBundledExecutableRunning(executable)) {
                Timber.i("Webserver process detected after %d attempts", i + 1);
                // NOTE: do NOT unlink logPath here. The process still holds
                // stdout/stderr open on it and writes there until it exits;
                // removing the name while a live fd is open would discard any
                // output from a later crash. The fixed path is truncated at the
                // next launch anyway, so leaving it in place costs nothing.
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        
        // If process didn't start, dump last lines from log for diagnostics.
        // BUG FIX: use a root shell (cat/tail) rather than java.io/nio file
        // APIs to read this file. It was created by the root shell in
        // /data/local/tmp, and on many ROMs the app's own (non-root)
        // process is blocked from reading it by SELinux even when the DAC
        // permissions look world-readable — so the previous
        // Files.readAllLines() call would silently fail here on exactly
        // the devices where this diagnostic is most needed.
        Shell.Result tailResult = Shell.cmd("tail -n 30 " + escapedString(logPath)).exec();
        if (tailResult.isSuccess() && !tailResult.getOut().isEmpty()) {
            Timber.e("Webserver failed to start, log (last %d lines) from %s:\n%s",
                    tailResult.getOut().size(), logPath, mergeAllLines(tailResult.getOut()));
        } else {
            Timber.e("Webserver failed to start and log at %s could not be read (exit code %d).",
                    logPath, tailResult.getCode());
        }
        // BUG FIX: don't delete the log on failure. Timber only reaches
        // logcat on debug builds or with the in-app debug preference
        // enabled (see ApplicationLog.init()), so on a normal release
        // build this file was the only surviving copy of the failure
        // reason — and it was being deleted moments after being written,
        // making the root cause of "Web server failed to start" reports
        // unrecoverable. Leave it in /data/local/tmp so it can still be
        // pulled with a root file manager or `adb shell` after the fact.

        return false;
    }

    /**
     * Best-effort deletion of a temporary launch log written to
     * /data/local/tmp, ignoring any failure since it is not critical to the
     * caller's outcome.
     */
    private static void deleteLogFile(String logPath) {
        try {
            Shell.cmd("rm -f " + escapedString(logPath)).exec();
        } catch (Exception ignored) {
        }
    }

    /**
     * Run a bundled executable synchronously (blocking until it exits), for
     * one-shot invocations whose result must be available before the caller
     * continues — e.g. generating the web server's TLS certificate before
     * starting the server with it.
     *
     * @return <code>true</code> if the executable ran and exited successfully.
     */
    public static boolean runBundledExecutableSync(Context context, String executable, String parameters) {
        String cmd = buildCommand(context, executable, parameters);
        if (cmd == null) {
            return false;
        }
        Timber.d("Executing sync: %s", cmd);
        
        Shell.Result result = Shell.cmd(cmd).exec();
        boolean success = result.isSuccess();
        
        if (!success) {
            Timber.e("Sync command failed with exit code %d: %s", result.getCode(), mergeAllLines(result.getErr()));
        } else {
            Timber.i("Sync command succeeded");
        }
        
        return success;
    }

    private static String buildCommand(Context context, String executable, String parameters) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String binPath = stageExecutable(context, executable);
        if (binPath == null) {
            return null;
        }
        return "LD_LIBRARY_PATH=" + nativeLibraryDir + " " + binPath + " " + parameters;
    }

    public static void killBundledExecutable(String executable) {
        // Try pkill/pkill -f then killall as fallback; ignore errors
        Shell.cmd("pkill -f '" + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX + "' || killall '" + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX + "' || true").exec();
    }
    /**
     * Read the last lines of the bundled executable launch log (written by
     * the root shell during runBundledExecutable). The file is root-owned,
     * so it must be read through a root shell; returns an empty string when
     * the log is unavailable or empty.
     */
    public static String readBundledExecutableStartLog(String executable) {
        try {
            String logPath = "/data/local/tmp/" + executable + "_start.log";
            Shell.Result r = Shell.cmd("tail -n 30 " + escapedString(logPath) + " 2>/dev/null").exec();
            if (r.isSuccess() && r.getOut() != null && !r.getOut().isEmpty()) {
                return mergeAllLines(r.getOut());
            }
        } catch (Exception ignored) {}
        return "";
    }



    /**
     * Check if a path is writable.
     *
     * @param file The file to check.
     * @return <code>true</code> if the path is writable, <code>false</code> otherwise.
     */
    public static boolean isWritable(File file) {
        // Check first if file can be written without privileges
        if (file.canWrite()) {
            return true;
        }
        return Shell.cmd("test -w " + escapedString(file.getAbsolutePath()))
                .exec()
                .isSuccess();
    }

    public static boolean remountPartition(File file, MountType type) {
        Optional<String> partitionOptional = findPartition(file);
        if (!partitionOptional.isPresent()) {
            return false;
        }
        String partition = partitionOptional.get();
        Shell.Result result = Shell.cmd("mount -o " + type.getOption() + ",remount " + partition).exec();
        boolean success = result.isSuccess();
        if (!success) {
            Timber.w("Failed to remount partition %s as %s: %s.", partition, type.getOption(), mergeAllLines(result.getErr()));
        }
        return success;
    }

    private static Optional<String> findPartition(File file) {
        // Get mount points
        Shell.Result result = Shell.cmd("cat /proc/mounts | cut -d ' ' -f2").exec();
        List<String> out = result.getOut();
        // Check file and each parent against mount points
        while (file != null) {
            String path = file.getAbsolutePath();
            for (String mount : out) {
                if (path.equals(mount)) {
                    return Optional.of(mount);
                }
            }
            file = file.getParentFile();
        }
        return Optional.empty();
    }
}
