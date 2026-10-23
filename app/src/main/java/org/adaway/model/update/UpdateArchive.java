package org.adaway.model.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Android APK checks; ELF headers are inspected without extracting executable files. */
final class UpdateArchive {
    private UpdateArchive() { }

    static boolean digestMatches(File apk, String expected) throws IOException {
        if (expected == null || !expected.matches("[0-9a-fA-F]{64}")) return false;
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (InputStream input = new FileInputStream(apk)) {
            byte[] bytes = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(bytes)) != -1) {
                total += count;
                if (total > 64L * 1024 * 1024) return false;
                digest.update(bytes, 0, count);
            }
        }
        byte[] actual = digest.digest();
        int different = 0;
        for (int i = 0; i < actual.length; i++) {
            int value = Character.digit(expected.charAt(i * 2), 16) * 16
                    + Character.digit(expected.charAt(i * 2 + 1), 16);
            different |= (actual[i] & 255) ^ value;
        }
        return different == 0;
    }

    static boolean nativeAbiMatches(File apk, String abi) throws IOException {
        int machine = "arm64-v8a".equals(abi) ? 183 : "x86_64".equals(abi) ? 62 : "riscv64".equals(abi) ? 243 : -1;
        if (machine < 0) return false;
        boolean server = false, capture = false;
        String directory = "lib/" + abi + "/";
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > 10000) return false;
                String name = entry.getName();
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue;
                if (!name.startsWith(directory) || name.substring(directory.length()).contains("/")) return false;
                byte[] header = new byte[64];
                try (InputStream input = zip.getInputStream(entry)) {
                    int offset = 0, read;
                    while (offset < header.length && (read = input.read(header, offset, header.length - offset)) > 0) offset += read;
                    if (offset != header.length) return false;
                }
                if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                        || header[4] != 2 || header[5] != 1 || header[6] != 1
                        || header[16] != 3 || header[17] != 0
                        || ((header[18] & 255) | (header[19] & 255) << 8) != machine) return false;
                boolean executable = name.equals(directory + "libwebserver_exec.so") || name.equals(directory + "libtcpdump_exec.so");
                if (executable) {
                    if (!androidExecutable(zip, entry, header)) return false;
                    if (name.endsWith("/libwebserver_exec.so")) { if (server) return false; server = true; }
                    else { if (capture) return false; capture = true; }
                }
            }
        }
        return server && capture;
    }

    private static boolean androidExecutable(ZipFile zip, ZipEntry entry, byte[] header) throws IOException {
        ByteBuffer elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long point = elf.getLong(24), offset = elf.getLong(32);
        int size = elf.getShort(54) & 65535, count = elf.getShort(56) & 65535;
        if (point <= 0 || offset < 64 || offset > 65536 || size != 56 || count == 0 || count > 128) return false;
        byte[] table = prefix(zip, entry, (int) offset + count * size);
        if (table == null) return false;
        ByteBuffer program = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        boolean interpreter = false, executableEntry = false;
        byte[] androidLinker = "/system/bin/linker64\0".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < count; i++) {
            int position = (int) offset + i * size;
            int kind = program.getInt(position), flags = program.getInt(position + 4);
            long fileOffset = program.getLong(position + 8), address = program.getLong(position + 16);
            long bytes = program.getLong(position + 32);
            if (fileOffset < 0 || bytes < 0 || fileOffset > entry.getSize() || bytes > entry.getSize() - fileOffset) return false;
            if (kind == 1 && (flags & 1) != 0 && address >= 0 && point >= address && point - address < bytes) executableEntry = true;
            if (kind == 3) {
                if (interpreter || bytes != androidLinker.length || fileOffset > 65536) return false;
                byte[] text = prefix(zip, entry, (int) fileOffset + androidLinker.length);
                if (text == null || !Arrays.equals(androidLinker,
                        Arrays.copyOfRange(text, (int) fileOffset, text.length))) return false;
                interpreter = true;
            }
        }
        return interpreter && executableEntry;
    }

    private static byte[] prefix(ZipFile zip, ZipEntry entry, int count) throws IOException {
        if (count > entry.getSize()) return null;
        byte[] bytes = new byte[count];
        try (InputStream input = zip.getInputStream(entry)) {
            int offset = 0, read;
            while (offset < count && (read = input.read(bytes, offset, count - offset)) > 0) offset += read;
            return offset == count ? bytes : null;
        }
    }
}
