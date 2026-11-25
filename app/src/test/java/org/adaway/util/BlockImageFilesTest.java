package org.adaway.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class BlockImageFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void largeImagesAreSampledInBothOrientations() {
        assertEquals(1, BlockImageFiles.sampleSize(512, 768));
        assertEquals(4, BlockImageFiles.sampleSize(8000, 2000));
        assertEquals(4, BlockImageFiles.sampleSize(2000, 8000));
        assertEquals(2, BlockImageFiles.sampleSize(2049, 1));
    }

    @Test public void failedEncodingLeavesLiveImageUnchanged() throws IOException {
        Path target = folder.getRoot().toPath().resolve("img_01.webp");
        byte[] original = {1, 2, 3};
        Files.write(target, original);
        try {
            BlockImageFiles.replace(target, out -> {
                out.write(9);
                throw new IOException("encoding failed");
            });
            fail("Expected encoding failure");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(target));
        }
        try {
            BlockImageFiles.replace(target, out -> {});
            fail("Expected empty file failure");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(target));
        }
        assertEquals(1, folder.getRoot().list().length);
        BlockImageFiles.replace(target, out -> out.write(new byte[]{4, 5, 6}));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(target));
        assertEquals(1, folder.getRoot().list().length);
    }
}
