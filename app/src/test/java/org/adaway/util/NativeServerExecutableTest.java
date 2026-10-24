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
            assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile(), "arm64-v8a"));
            elf.putLong(24, 0x10000);
            Files.write(file, elf.array());
            NativeServerExecutable.verify(file.toFile(), "arm64-v8a");
            Files.write(file, new byte[4]);
            assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile(), "arm64-v8a"));
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void selectedAbiMustMatchActualElfMachine() throws Exception {
        Path file = Files.createTempFile("adaway-server-abi", ".so");
        String[] abis = {"arm64-v8a", "x86_64", "riscv64"};
        int[] machines = {183, 62, 243};
        try {
            ByteBuffer elf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            elf.putInt(0, 0x464c457f).put(4, (byte)2).put(5, (byte)1).putShort(16, (short)3).putLong(24, 0x10000);
            for (int index = 0; index < abis.length; index++) {
                elf.putShort(18, (short)machines[index]);
                Files.write(file, elf.array());
                NativeServerExecutable.verify(file.toFile(), abis[index]);
                for (String other : abis) if (!other.equals(abis[index]))
                    assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile(), other));
            }
            assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile(), "armeabi-v7a"));
            assertThrows(IOException.class, () -> NativeServerExecutable.verify(file.toFile(), null));
        } finally { Files.deleteIfExists(file); }
    }
}
