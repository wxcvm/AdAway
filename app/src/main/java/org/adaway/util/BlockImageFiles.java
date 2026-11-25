package org.adaway.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Pure file and dimension helpers, independently testable without Android. */
final class BlockImageFiles {
    private BlockImageFiles() { }
    static int sampleSize(int width, int height) {
        int sample = 1;
        while ((long) Math.max(width, height) > 2048L * sample) sample *= 2;
        return sample;
    }
    interface Encoder { void write(OutputStream stream) throws IOException; }
    static void replace(Path target, Encoder encoder) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), ".image-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(staged)) { encoder.write(out); }
            if (Files.size(staged) == 0) throw new IOException("Empty encoded image");
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }
}
