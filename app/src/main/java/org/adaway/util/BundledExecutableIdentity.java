package org.adaway.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Identity checks belong to lifecycle entry points, never the statistics polling path. */
public final class BundledExecutableIdentity {
    private BundledExecutableIdentity() { }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768];
            try (FileInputStream input = new FileInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    /** Require BOTH the complete first argv and the kernel's actual executable path. */
    public static String ownershipFunctions(String executablePath) {
        return "owned_bin=" + quote(executablePath) + "\n"
                + "owned_candidates() {\n"
                + "  if command -v pgrep >/dev/null 2>&1; then pgrep -f \"$owned_bin\" 2>/dev/null | sed 's,^,/proc/,'; else printf '%s\\n' /proc/[0-9]*; fi\n"
                + "}\n"
                + "owned_process() {\n"
                + "  [ \"$(tr '\\000' '\\n' 2>/dev/null < \"$1/cmdline\" | head -n 1)\" = \"$owned_bin\" ] || return 1\n"
                + "  owned_exe=$(readlink \"$1/exe\" 2>/dev/null) || return 1\n"
                + "  case \"$owned_exe\" in \"$owned_bin\"|\"$owned_bin (deleted)\") return 0;; *) return 1;; esac\n"
                + "}\n"
                + "process_token() {\n"
                + "  process_start=$(sed 's/.*) //' \"$1/stat\" 2>/dev/null | cut -d' ' -f20)\n"
                + "  process_inode=$(stat -Lc '%d:%i' \"$1/exe\" 2>/dev/null) || return 1\n"
                + "  [ -n \"$process_start\" ] || return 1\n"
                + "  printf '%s:%s' \"$process_start\" \"$process_inode\"\n"
                + "}\n";
    }

    public static String scanCommand(String executablePath, boolean hashImage) {
        return scanProcesses(executablePath, "$(owned_candidates)", hashImage);
    }

    /** Restrict an isolated fixture to its own child, including when other toybox processes exist. */
    public static String scanPidCommand(String executablePath, long pid, boolean hashImage) {
        if (pid <= 0) throw new IllegalArgumentException("Invalid process id");
        return scanProcesses(executablePath, "/proc/" + pid, hashImage);
    }

    private static String scanProcesses(String executablePath, String processes, boolean hashImage) {
        return ownershipFunctions(executablePath)
                + "for owned_proc in " + processes + "; do\n"
                + "  owned_process \"$owned_proc\" || continue\n"
                + "  before=$(process_token \"$owned_proc\") || { echo UNKNOWN; continue; }\n"
                + (hashImage ? "  image_hash=$(sha256sum \"$owned_proc/exe\" 2>/dev/null | cut -d' ' -f1)\n" : "  image_hash=present\n")
                + "  after=$(process_token \"$owned_proc\") || { echo UNKNOWN; continue; }\n"
                + "  if [ \"$before\" = \"$after\" ] && owned_process \"$owned_proc\"; then\n"
                + "    printf '%s|%s|%s\\n' \"${owned_proc##*/}\" \"$before\" \"$image_hash\"\n"
                + "  else echo UNKNOWN; fi\n"
                + "done\n";
    }

    /** An empty/partial/ambiguous scan must never certify the installed version. */
    public static boolean allImagesMatch(List<String> lines, String expectedHash) {
        if (lines.isEmpty() || expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) return false;
        for (String line : lines) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 3 || !fields[0].matches("[0-9]+")
                    || !fields[1].matches("[0-9]+:[0-9]+:[0-9]+") || !expectedHash.equals(fields[2])) return false;
        }
        return true;
    }

    public static String stopCommand(String executablePath) {
        return stopProcesses(executablePath, "$(owned_candidates)");
    }

    public static String stopPidCommand(String executablePath, long pid) {
        if (pid <= 0) throw new IllegalArgumentException("Invalid process id");
        return stopProcesses(executablePath, "/proc/" + pid);
    }

    private static String stopProcesses(String executablePath, String processes) {
        return ownershipFunctions(executablePath)
                + "for owned_proc in " + processes + "; do\n"
                + "  owned_process \"$owned_proc\" || continue\n"
                + "  before=$(process_token \"$owned_proc\") || continue\n"
                + "  after=$(process_token \"$owned_proc\") || continue\n"
                + "  if [ \"$before\" = \"$after\" ] && owned_process \"$owned_proc\"; then kill -TERM \"${owned_proc##*/}\" || exit 1; fi\n"
                + "done\n";
    }

    /** Same-filesystem rename never truncates a running image (ETXTBSY) or the last copy. */
    public static String stageCommand(String source, String target) {
        return ownershipFunctions(target)
                + "stage_source=" + quote(source) + "\nstage_target=" + quote(target) + "\n"
                + "stage_new=\"$stage_target.new.$$\"\n"
                + "  if cp \"$stage_source\" \"$stage_new\" && chmod 755 \"$stage_new\" && cmp -s \"$stage_source\" \"$stage_new\"; then\n"
                + "    previous_proc=; previous_count=0\n"
                + "    for owned_proc in $(owned_candidates); do\n"
                + "      if owned_process \"$owned_proc\"; then previous_proc=$owned_proc; previous_count=$((previous_count+1)); fi\n"
                + "    done\n"
                + "    [ \"$previous_count\" -le 1 ] || { rm -f \"$stage_new\"; exit 1; }\n"
                + "    if [ \"$previous_count\" = 1 ]; then\n"
                + "      previous_token=$(process_token \"$previous_proc\") || { rm -f \"$stage_new\"; exit 1; }\n"
                + "      previous_new=\"$stage_target.previous.new.$$\"\n"
                + "      if cp \"$previous_proc/exe\" \"$previous_new\" && chmod 755 \"$previous_new\" && cmp -s \"$previous_proc/exe\" \"$previous_new\" && [ \"$previous_token\" = \"$(process_token \"$previous_proc\")\" ] && owned_process \"$previous_proc\"; then\n"
                + "        mv -f \"$previous_new\" \"$stage_target.previous\" || { rm -f \"$stage_new\" \"$previous_new\"; exit 1; }\n"
                + "      else rm -f \"$stage_new\" \"$previous_new\"; exit 1; fi\n"
                + "    fi\n"
                + "    mv -f \"$stage_new\" \"$stage_target\" || { rm -f \"$stage_new\"; exit 1; }\n"
                + "  else rm -f \"$stage_new\"; exit 1; fi\n";
    }
}
