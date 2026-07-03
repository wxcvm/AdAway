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
        try {
            // Prefer pgrep (less parsing) when available
            Shell.Result r = Shell.cmd("pgrep -f '" + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX + "' >/dev/null 2>&1").exec();
            if (r.isSuccess()) return true;
        } catch (Exception ignored) {}
        // Fallback to ps + guarded grep to avoid matching the grep process itself
        String grepCmd = "ps -A 2>/dev/null | grep -E \"[l]ib" + executable + "_exec\\.so\" >/dev/null 2>&1";
        return Shell.cmd(grepCmd).exec().isSuccess();
    }

    public static boolean runBundledExecutable(Context context, String executable, String parameters) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String binPath = nativeLibraryDir + File.separator + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX;
        
        // Use /data/local/tmp for logging instead of app filesDir to avoid permission issues
        String logPath = "/data/local/tmp/webserver_start_" + System.currentTimeMillis() + ".log";

        // Start in background, redirect stdout/stderr to a log file
        String cmd = "LD_LIBRARY_PATH=" + nativeLibraryDir + " " + binPath + " " + parameters +
                " > " + logPath + " 2>&1 &";
        
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
                // The process still holds its stdout/stderr open on this file;
                // unlinking it now (rather than leaving it behind) prevents
                // /data/local/tmp from accumulating one log file per launch
                // (e.g. every boot or every time the web server is toggled)
                // while the process keeps writing to it until it exits.
                deleteLogFile(logPath);
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
        return "LD_LIBRARY_PATH=" + nativeLibraryDir + " " +
                nativeLibraryDir + File.separator + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX + " " +
                parameters;
    }

    public static void killBundledExecutable(String executable) {
        // Try pkill/pkill -f then killall as fallback; ignore errors
        Shell.cmd("pkill -f '" + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX + "' || killall '" + EXECUTABLE_PREFIX + executable + EXECUTABLE_SUFFIX + "' || true").exec();
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
