package org.adaway.model.root;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

public class HijackCleanupCommandsTest {
    private static int run(int hooks, String error) throws Exception {
        Path folder = Files.createTempDirectory("hijack-shell-test");
        Path command = folder.resolve("iptables"), state = folder.resolve("hooks");
        Files.writeString(state, Integer.toString(hooks));
        Files.writeString(command, "#!/bin/sh\n"
                + "case \"$*\" in\n"
                + " '-t nat -S') [ \"$ERROR_MODE\" != global ] || exit 2; exit 0 ;;\n"
                + " '-t nat -C OUTPUT -j ADBLOCK_HIJACK')\n"
                + " [ \"$ERROR_MODE\" != query ] || exit 2\n"
                + " [ \"$(cat \"$HOOKS\")\" -gt 0 ]; exit $? ;;\n"
                + " '-t nat -D OUTPUT -j ADBLOCK_HIJACK')\n"
                + " [ \"$ERROR_MODE\" != delete ] || exit 2\n"
                + " value=$(cat \"$HOOKS\"); echo $((value - 1)) > \"$HOOKS\"; exit 0 ;;\n"
                + " '-t nat -S ADBLOCK_HIJACK'|'-t nat -F ADBLOCK_HIJACK'|'-t nat -X ADBLOCK_HIJACK') exit 0 ;;\n"
                + " *) exit 2 ;;\nesac\n");
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", HijackCleanupCommands.script("ADBLOCK_HIJACK"));
        builder.environment().put("PATH", folder + ":" + builder.environment().get("PATH"));
        builder.environment().put("HOOKS", state.toString());
        builder.environment().put("ERROR_MODE", error);
        try {
            Process process = builder.start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("cleanup shell stuck"); }
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() == 0) {
                assertEquals("CLEAN", output.trim());
                assertEquals("0", Files.readString(state).trim());
            } else assertFalse(output.contains("CLEAN"));
            return process.exitValue();
        } finally {
            Files.deleteIfExists(command); Files.deleteIfExists(state); Files.deleteIfExists(folder);
        }
    }
    @Test public void removesDuplicateHooksAndBoundsWork() throws Exception {
        assertEquals(0, run(0, ""));
        assertEquals(0, run(3, ""));
        assertNotEquals(0, run(33, ""));
    }
    @Test public void neverClaimsCleanOnInspectionOrDeletionFailure() throws Exception {
        assertNotEquals(0, run(0, "global"));
        assertNotEquals(0, run(1, "query"));
        assertNotEquals(0, run(1, "delete"));
    }
    @Test public void rejectsShellInput() {
        try { HijackCleanupCommands.script("X; echo unsafe"); fail("unvalidated chain accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
