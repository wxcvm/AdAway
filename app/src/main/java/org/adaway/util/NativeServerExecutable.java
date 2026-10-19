package org.adaway.util;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fail before stopping a healthy instance when the installed APK contains a placeholder. */
public final class NativeServerExecutable {
    private NativeServerExecutable() { }

    public static void verify(File file) throws IOException {
        byte[] header = new byte[32];
        try (DataInputStream stream = new DataInputStream(new FileInputStream(file))) {
            stream.readFully(header);
        }
        ByteBuffer elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (elf.getInt(0) != 0x464c457f || header[4] != 2 || header[5] != 1
                || elf.getShort(16) != 3 || elf.getShort(18) != 183 || elf.getLong(24) == 0)
            throw new IOException("安装包中的 Web 服务器不是有效的 ARM64 可执行程序，请更新修复版本");
    }
}
