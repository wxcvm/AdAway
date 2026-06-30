package org.adaway.model.root;

import static com.topjohnwu.superuser.ShellUtils.escapedString;

import android.content.Context;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
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
        String logPath = context.getFilesDir().getAbsolutePath() + "/webserver_start.log";

        // Ensure parent dir exists
        try {
            Files.createDirectories(Paths.get(context.getFilesDir().getAbsolutePath()));
        } catch (Exception ignored) {}

        // Start in background, redirect stdout/stderr to a log file
        String cmd = "LD_LIBRARY_PATH=" + nativeLibraryDir + " " + binPath + " " + parameters +
                " > " + logPath + " 2>&1 &";
        Shell.Result result = Shell.cmd(cmd).exec();
        if (!result.isSuccess()) {
            Timber.e("Launch command failed: %s", mergeAllLines(result.getErr()));
            return false;
        }
        // Wait briefly for the process to appear
        for (int i = 0; i < 10; i++) {
            if (isBundledExecutableRunning(executable)) return true;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        // If process didn't start, dump last lines from log for diagnostics
        try {
            List<String> lines = Files.readAllLines(Paths.get(logPath));
            int from = Math.max(0, lines.size() - 20);
            List<String> tail = lines.subList(from, lines.size());
            Timber.e("Webserver failed to start, log (last %d lines):\n%s", tail.size(), mergeAllLines(tail));
        } catch (Exception e) {
            Timber.w(e, "Could not read webserver log.");
        }
        return false;
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
        return Shell.cmd(buildCommand(context, executable, parameters)).exec().isSuccess();
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
