package org.adaway.util;

import org.junit.Assume;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Linux CI exercises real deleted executable mappings and failure preservation without su/network. */
public class BundledExecutableLifecycleTest {
    @Test public void stagedNewPathCannotHideOldMappedImageAndBackupUsesActualImage() throws Exception {
        Assume.assumeTrue(new File("/proc/self/exe").exists() && new File("/bin/sleep").exists());
        Path directory = Files.createTempDirectory("adaway-image-");
        Path target = directory.resolve("libwebserver_exec.so");
        Path source = directory.resolve("current-image");
        Path previous = Path.of(target + ".previous");
        Files.copy(Path.of("/bin/sleep"), target);
        assertTrue(target.toFile().setExecutable(true));
        String oldHash = BundledExecutableIdentity.sha256(target.toFile());
        Process old = new ProcessBuilder(target.toString(), "30").start();
        Process foreign = new ProcessBuilder("/bin/sh", "-c", "read ignored", target.toString()).start();
        Process second = null;
        try {
            byte[] bytes = Files.readAllBytes(target);
            byte[] updated = Arrays.copyOf(bytes, bytes.length + 1);
            updated[updated.length - 1] = 42;
            Files.write(source, updated);
            String currentHash = BundledExecutableIdentity.sha256(source.toFile());
            Path staged = directory.resolve("pre-staged-new");
            Files.copy(source, staged);
            assertTrue(staged.toFile().setExecutable(true));
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            List<String> actual = output(BundledExecutableIdentity.scanPidCommand(target.toString(), old.pid(), true));
            assertTrue(actual.toString(), BundledExecutableIdentity.allImagesMatch(actual, oldHash));
            assertFalse(BundledExecutableIdentity.allImagesMatch(actual, currentHash));
            assertTrue(output(BundledExecutableIdentity.scanPidCommand(target.toString(), foreign.pid(), true)).isEmpty());
            run(BundledExecutableIdentity.stopPidCommand(target.toString(), foreign.pid()), 0);
            assertTrue("substring argument process must survive", foreign.isAlive());

            run(BundledExecutableIdentity.stageCommand(source.toString(), target.toString()), 0);
            assertEquals(currentHash, BundledExecutableIdentity.sha256(target.toFile()));
            assertEquals("backup must come from old mapped image, not the already-new path", oldHash,
                    BundledExecutableIdentity.sha256(previous.toFile()));
            assertTrue(old.isAlive());

            run(BundledExecutableIdentity.stageCommand(directory.resolve("missing").toString(), target.toString()), 1);
            assertEquals(currentHash, BundledExecutableIdentity.sha256(target.toFile()));
            assertEquals(oldHash, BundledExecutableIdentity.sha256(previous.toFile()));
            assertTrue(old.isAlive());

            second = new ProcessBuilder(target.toString(), "30").start();
            run(BundledExecutableIdentity.stageCommand(source.toString(), target.toString()), 1);
            assertTrue("ambiguous mappings must not be stopped", old.isAlive() && second.isAlive());
            assertEquals(oldHash, BundledExecutableIdentity.sha256(previous.toFile()));
            run(BundledExecutableIdentity.stopPidCommand(target.toString(), old.pid()), 0);
            assertTrue(old.waitFor(3, TimeUnit.SECONDS));
            assertTrue(foreign.isAlive());
        } finally {
            old.destroy(); foreign.destroy();
            if (second != null) second.destroy();
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                for (Path entry : entries.toList()) Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static List<String> output(String command) throws Exception {
        String result = run(command, 0).trim();
        return result.isEmpty() ? List.of() : Arrays.asList(result.split("\\r?\\n"));
    }

    private static String run(String command, int expectedExit) throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
        assertTrue("lifecycle script timed out", process.waitFor(10, TimeUnit.SECONDS));
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(result, expectedExit, process.exitValue());
        return result;
    }
}
