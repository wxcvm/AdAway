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

        assertTrue("resources", script.contains("--resources \"" + RESOURCES + "\""));
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

    @Test
    public void uninstallGuardIsPresent() {
        String script = MagiskBootScript.buildScript(
                NATIVE_LIB, RESOURCES, false, 8080, 8443, 8686, false);
        assertTrue("removes itself when the app is gone",
                script.contains("app data gone - removing this boot script"));
    }
}
