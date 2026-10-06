package org.adaway.model.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the generated init-side boot script.
 *
 * <p>This is the only part of the boot path that can be checked without a rooted
 * device, and it is the part that silently diverged before: the script used to
 * omit {@code --stats-port} (so a boot-started server answered on a different
 * management port and the app thought it was not running) and omitted
 * {@code --proxy-filter} in hijack mode (so every non-blocked host was forwarded
 * unfiltered). Both are asserted here.</p>
 */
public class MagiskBootScriptTest {

    private static final String NATIVE_LIB = "/data/app/~~x==/org.adaway-y==/lib/arm64";
    private static final String RESOURCES = "/data/user/0/org.adaway/files/webserver";

    private static List<String> lines(String script) {
        List<String> out = new ArrayList<>();
        for (String line : script.split("\n")) {
            out.add(line);
        }
        return out;
    }

    @Test
    public void loopbackScriptCarriesEveryStartupFlag() {
        String script = MagiskBootScript.buildScript(
                NATIVE_LIB, RESOURCES, false, 8080, 8443, 8686, false);

        /*
         * The script defines each path ONCE and then refers to it as a shell
         * variable ("RES=…", "--resources \"$RES\""), so both halves have to be
         * asserted: the definition carries the absolute path, the flag carries
         * the variable. Asserting the expanded path on the flag line is what
         * broke this test the first time.
         */
        assertTrue("resources dir is defined", script.contains("RES=" + RESOURCES));
        assertTrue("resources flag uses the variable",
                script.contains("--resources \"$RES\""));
        assertTrue("app lib dir is defined", script.contains("APP_LIB=" + NATIVE_LIB));
        assertTrue("bind mode", script.contains("--bind loop"));
        assertTrue("http port", script.contains("--http-port 8080"));
        assertTrue("https port", script.contains("--https-port 8443"));
        assertTrue("management port (must match the app)", script.contains("--stats-port 8686"));
        assertFalse("no proxy-filter outside hijack mode", script.contains("--proxy-filter"));
        assertTrue("library path for libssl/libcrypto",
                script.contains("LD_LIBRARY_PATH=\"$APP_LIB\""));
        assertTrue("staged binary is used", script.contains("BIN=/data/local/tmp/lib"));
        assertTrue("script ends with exit 0", script.trim().endsWith("exit 0"));
    }

    @Test
    public void hijackModeAddsProxyFilter() {
        String script = MagiskBootScript.buildScript(
                NATIVE_LIB, RESOURCES, false, 8080, 8443, 8686, true);
        assertTrue("hijack mode must forward+filter", script.contains("--proxy-filter"));
    }

    @Test
    public void bindAllIsPassedThrough() {
        String script = MagiskBootScript.buildScript(
                NATIVE_LIB, RESOURCES, true, 18080, 18443, 8686, false);
        assertTrue(script.contains("--bind all"));
        assertTrue(script.contains("--http-port 18080"));
        assertTrue(script.contains("--https-port 18443"));
    }

    @Test
    public void scriptNeverBlocksTheBoot() {
        String script = MagiskBootScript.buildScript(
                NATIVE_LIB, RESOURCES, false, 8080, 8443, 8686, false);
        /* No unescaped % (the file is generated through a plain Java string, but
           a stray % in a shell script is a common copy/paste accident). */
        assertFalse("no stray percent", script.contains("%"));
        /* The wait loop must be bounded so a device that never reports
           sys.boot_completed cannot leave a shell running forever. */
        assertTrue("bounded boot wait", script.contains("[ $i -lt 150 ]"));
        /* Starting in the background and exiting immediately is what keeps the
           boot itself unaffected. */
        assertTrue("backgrounded start", script.contains(">> \"$LOG\" 2>&1 &"));
        assertEquals("one single script body line count is stable",
                1, lines(script).stream().filter(l -> l.startsWith("#!/system/bin/sh")).count());
    }

    /**
     * The uninstall guard must never conclude "the app is gone" from a missing
     * data directory alone.
     *
     * <p>service.d runs at late_start, which on an encrypted device is normally
     * still before the first unlock: {@code /data/user/0/<pkg>} is not visible
     * yet. Every boot until this fix logged "app data gone" and deleted the
     * script, so the init-side path never started the server - the reason the
     * server only came up after the app was opened.</p>
     */
    @Test
    public void uninstallGuardWaitsForUserStorageAndNeedsTwoBoots() {
        String script = MagiskBootScript.buildScript(
                NATIVE_LIB, RESOURCES, false, 8080, 8443, 8686, false);
        assertTrue("waits for the CE key before judging anything",
                script.contains("sys.user.0.ce_available"));
        assertTrue("keeps the script while storage is still locked",
                script.contains("user storage still locked - keeping this boot script"));
        assertTrue("cross-checks the package manager", script.contains("pm path"));
        assertTrue("keeps the script when the app is installed but data is not ready",
                script.contains("app installed, its data dir is not ready"));
        assertTrue("has a miss counter", script.contains("adblock-boot-miss"));
        assertTrue("deletes only on the second consecutive miss",
                script.contains("[ $n -ge 2 ]"));
        assertTrue("resets the counter once the app is found",
                script.contains("rm -f /data/local/tmp/adblock-boot-miss"));
        assertFalse("the old unconditional delete is gone",
                script.contains("app data gone - removing this boot script"));
    }
}
