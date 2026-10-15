package org.adaway.model.source;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.UnaryOperator;

/** Publishes complete files in one rename. Unsupported atomic replacement fails with the old file intact. */
public final class AtomicRuleFile {
    private AtomicRuleFile() { }
    public interface WriterAction { void write(java.io.Writer writer) throws IOException; }
    public static synchronized void write(File file, WriterAction action) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(), ".rule-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE);
                 java.io.Writer writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                         java.nio.channels.Channels.newOutputStream(channel), StandardCharsets.UTF_8))) {
                action.write(writer); writer.flush();
                if (channel.size() > BoundedSourceImport.MAX_CHARACTERS) throw new IOException("Rule file exceeds import limit");
                channel.force(true);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
    }
    public static synchronized void update(File file, UnaryOperator<String> edit) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && Files.size(target) > BoundedSourceImport.MAX_CHARACTERS)
            throw new IOException("Rule file exceeds import limit");
        // Files.readString is newer than our minimum Android runtime. Keep strict UTF-8 decoding on API 26+.
        String previous = Files.exists(target) ? StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(target))).toString() : "";
        byte[] next = edit.apply(previous).getBytes(StandardCharsets.UTF_8);
        if (next.length > BoundedSourceImport.MAX_CHARACTERS) throw new IOException("Rule file exceeds import limit");
        write(file, writer -> writer.write(new String(next, StandardCharsets.UTF_8)));
    }
}
