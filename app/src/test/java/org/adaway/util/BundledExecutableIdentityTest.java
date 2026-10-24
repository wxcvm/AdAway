package org.adaway.util;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

public class BundledExecutableIdentityTest {
    private static final String CURRENT = "a".repeat(64);
    private static final String OLD = "b".repeat(64);

    @Test public void staleMixedMissingAndUnverifiableImagesAreNeverCurrent() {
        assertTrue(BundledExecutableIdentity.allImagesMatch(List.of("123|456:7:8|" + CURRENT), CURRENT));
        assertFalse(BundledExecutableIdentity.allImagesMatch(List.of("123|456:7:8|" + OLD), CURRENT));
        assertFalse(BundledExecutableIdentity.allImagesMatch(List.of("123|456:7:8|" + CURRENT,
                "124|457:7:9|" + OLD), CURRENT));
        assertFalse(BundledExecutableIdentity.allImagesMatch(List.of(), CURRENT));
        assertFalse(BundledExecutableIdentity.allImagesMatch(List.of("UNKNOWN"), CURRENT));
        assertFalse(BundledExecutableIdentity.allImagesMatch(List.of("123|456:7:8|"), CURRENT));
        assertFalse(BundledExecutableIdentity.allImagesMatch(List.of("123|456:7:8|" + CURRENT), ""));
    }

    @Test public void hashIdentifiesActualBytes() throws Exception {
        Path file = Files.createTempFile("bundled-image", ".bin");
        try {
            Files.writeString(file, "abc");
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BundledExecutableIdentity.sha256(file.toFile()));
            Files.writeString(file, "abd");
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BundledExecutableIdentity.sha256(file.toFile()));
        } finally { Files.deleteIfExists(file); }
    }
}
