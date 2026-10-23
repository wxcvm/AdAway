package org.adaway.model.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Bounded archive inspection with synthetic ELF headers, never executing them. */
public class UpdateArchiveTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private byte[] header(int machine, boolean executable) {
        byte[] bytes = new byte[256];
        bytes[0] = 0x7f; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F';
        bytes[4] = 2; bytes[5] = 1; bytes[6] = 1; bytes[16] = 3;
        bytes[18] = (byte) machine; bytes[19] = (byte) (machine >> 8);
        if (executable) {
            java.nio.ByteBuffer elf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            elf.putLong(24, 1); elf.putLong(32, 64); elf.putShort(54, (short) 56); elf.putShort(56, (short) 2);
            elf.putInt(64, 1); elf.putInt(68, 5); elf.putLong(96, bytes.length);
            byte[] linker = "/system/bin/linker64\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            elf.putInt(120, 3); elf.putLong(128, 200); elf.putLong(152, linker.length);
            System.arraycopy(linker, 0, bytes, 200, linker.length);
        }
        return bytes;
    }

    private File archive(String abi, int machine, boolean capture, boolean executable, boolean foreign) throws Exception {
        File file = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            zip.putNextEntry(new ZipEntry("lib/" + abi + "/libwebserver_exec.so"));
            zip.write(header(machine, executable)); zip.closeEntry();
            if (capture) {
                zip.putNextEntry(new ZipEntry("lib/" + abi + "/libtcpdump_exec.so"));
                zip.write(header(machine, executable)); zip.closeEntry();
            }
            if (foreign) {
                zip.putNextEntry(new ZipEntry("lib/x86_64/libunexpected.so"));
                zip.write(header(62, false)); zip.closeEntry();
            }
        }
        return file;
    }

    @Test public void bothPackagedExecutablesMustMatchTheSelectedAbiAndHaveEntryPoints() throws Exception {
        String[] abis = {"arm64-v8a", "x86_64", "riscv64"};
        int[] machines = {183, 62, 243};
        for (int i = 0; i < abis.length; i++) {
            File file = archive(abis[i], machines[i], true, true, false);
            assertTrue(UpdateArchive.nativeAbiMatches(file, abis[i]));
            assertFalse(UpdateArchive.nativeAbiMatches(file, abis[(i + 1) % abis.length]));
        }
        assertFalse(UpdateArchive.nativeAbiMatches(archive("riscv64", 183, true, true, false), "riscv64"));
        assertFalse(UpdateArchive.nativeAbiMatches(archive("riscv64", 243, false, true, false), "riscv64"));
        assertFalse(UpdateArchive.nativeAbiMatches(archive("riscv64", 243, true, false, false), "riscv64"));
        assertFalse(UpdateArchive.nativeAbiMatches(archive("riscv64", 243, true, true, true), "riscv64"));
        assertFalse(UpdateArchive.nativeAbiMatches(archive("riscv64", 243, true, true, false), "linux-riscv64"));
    }

    @Test public void malformedNativeDataAndNestedLibraryPathsAreRejected() throws Exception {
        File file = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            zip.putNextEntry(new ZipEntry("lib/riscv64/libwebserver_exec.so")); zip.write(new byte[8]); zip.closeEntry();
        }
        assertFalse(UpdateArchive.nativeAbiMatches(file, "riscv64"));
        assertFalse(UpdateArchive.nativeAbiMatches(archive("riscv64/nested", 243, true, true, false), "riscv64"));
        File linux = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(linux.toPath()))) {
            byte[] bytes = header(243, true);
            bytes[200] = 'L'; // Correct machine number alone does not identify Android's linker.
            zip.putNextEntry(new ZipEntry("lib/riscv64/libwebserver_exec.so")); zip.write(bytes); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/riscv64/libtcpdump_exec.so")); zip.write(bytes); zip.closeEntry();
        }
        assertFalse(UpdateArchive.nativeAbiMatches(linux, "riscv64"));
    }

    @Test public void checksumIsForTheActualArchiveAndRejectsMissingMalformedOrDifferentDigests() throws Exception {
        File file = temporary.newFile(); Files.write(file.toPath(), new byte[]{'a', 'b', 'c'});
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertTrue(UpdateArchive.digestMatches(file, hash));
        assertTrue(UpdateArchive.digestMatches(file, hash.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(UpdateArchive.digestMatches(file, ""));
        assertFalse(UpdateArchive.digestMatches(file, "sha256:" + hash));
        Files.write(file.toPath(), new byte[]{'a', 'b', 'd'});
        assertFalse(UpdateArchive.digestMatches(file, hash));
    }
}
