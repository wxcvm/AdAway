package org.adaway.model.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtomicRuleFileTest {
    @Test public void failedEditPreservesOldFileAndConcurrentReadersSeeCompleteVersions() throws Exception {
        Path directory = Files.createTempDirectory("adaway-rule-file-");
        Path path = directory.resolve("rules.txt");
        String first = "first.test\n".repeat(1000), second = "second.test\n".repeat(1000);
        AtomicRuleFile.update(path.toFile(), old -> first);
        try { AtomicRuleFile.update(path.toFile(), old -> { throw new IllegalArgumentException("rejected"); }); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(first, Files.readString(path)); }
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { for (int i = 0; i < 100; i++) {
                final String next = i % 2 == 0 ? second : first;
                for (int attempt = 0; ; attempt++) {
                    try { AtomicRuleFile.update(path.toFile(), old -> next); break; }
                    catch (java.nio.file.AccessDeniedException e) {
                        // Windows may deny replace while the test reader has the target open.
                        // The production Android writer uses POSIX rename. A denied move must preserve the old file.
                        if (!System.getProperty("os.name").startsWith("Windows") || attempt == 100) throw e;
                        Thread.sleep(1);
                    }
                }
            } }
            catch (Throwable e) { error.set(e); }
        });
        writer.start();
        try {
            for (int i = 0; i < 1000; i++) { String seen = Files.readString(path); assertTrue(seen.equals(first) || seen.equals(second)); }
            writer.join(10000); assertFalse(writer.isAlive()); assertNull(error.get());
            try (java.util.stream.Stream<Path> children = Files.list(directory)) { assertEquals(1, children.count()); }
        } finally { writer.join(); Files.deleteIfExists(path); Files.deleteIfExists(directory); }
    }
}
