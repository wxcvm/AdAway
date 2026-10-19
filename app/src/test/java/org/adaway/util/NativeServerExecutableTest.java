package org.adaway.util;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.Assert.assertThrows;

public class NativeServerExecutableTest {
    @Test public void zeroEntryLibraryIsRejectedBeforeRootOrRestart() throws Exception {
        Path file = Files.createTempFile("adaway-server", ".so");
        try {
            ByteBuffer elf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            elf.putInt(0, 0x464c457f).put(4, (byte)2).put(5, (byte)1).putShort(16, (short)3).putShort(18, (short)183);
            Files.write(file, elf.array());
            assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile()));
            elf.putLong(24, 0x10000);
            Files.write(file, elf.array());
            NativeServerExecutable.verify(file.toFile());
            Files.write(file, new byte[4]);
            assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile()));
        } finally { Files.deleteIfExists(file); }
    }
}
