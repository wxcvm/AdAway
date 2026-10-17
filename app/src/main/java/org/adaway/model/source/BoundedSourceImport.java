package org.adaway.model.source;

import java.io.IOException;
import java.io.Reader;

/** Streaming import called inside the source transaction. No worker can outlive it. */
public final class BoundedSourceImport {
    public static final int MAX_LINE = 4096, MAX_ENTRIES = 500000;
    public static final long MAX_CHARACTERS = 64L * 1024 * 1024;
    public interface Sink {
        void entry(DomainSourceParser.Entry entry) throws IOException;
        void cosmetic(String line) throws IOException;
    }
    private BoundedSourceImport() { }
    public static int read(Reader reader, boolean allowSource, boolean redirects, Sink sink) throws IOException {
        return read(reader, allowSource, redirects, sink, MAX_CHARACTERS, MAX_LINE, MAX_ENTRIES);
    }
    static int read(Reader reader, boolean allowSource, boolean redirects, Sink sink,
                    long maxCharacters, int maxLine, int maxEntries) throws IOException {
        StringBuilder line = new StringBuilder();
        long characters = 0;
        int count = 0, value;
        while ((value = reader.read()) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Source import interrupted");
            if (++characters > maxCharacters) throw new IOException("Source exceeds character limit");
            if (value == 0) throw new IOException("NUL in source");
            if (value == '\n') {
                count = consume(line.toString(), allowSource, redirects, sink, count, maxEntries);
                line.setLength(0);
            } else {
                if (line.length() >= maxLine) throw new IOException("Source line exceeds limit");
                line.append((char) value);
            }
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Source import interrupted");
        if (line.length() != 0) count = consume(line.toString(), allowSource, redirects, sink, count, maxEntries);
        return count;
    }
    private static int consume(String line, boolean allowSource, boolean redirects, Sink sink,
                               int count, int maxEntries) throws IOException {
        final DomainSourceParser.Parsed parsed;
        try { parsed = DomainSourceParser.parse(line, allowSource, redirects); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid domain source line", e); }
        if (parsed.unsupported) RuleModifiers.noteSkipped();
        if (parsed.cosmetic) sink.cosmetic(line);
        for (DomainSourceParser.Entry entry : parsed.entries) {
            if (++count > maxEntries) throw new IOException("Source exceeds entry limit");
            sink.entry(entry);
        }
        return count;
    }
}
