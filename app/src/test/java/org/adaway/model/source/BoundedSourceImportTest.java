package org.adaway.model.source;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedSourceImportTest {
    private static BoundedSourceImport.Sink sink(List<String> entries) {
        return new BoundedSourceImport.Sink() {
            public void entry(DomainSourceParser.Entry e) { entries.add(e.domain); }
            public void cosmetic(String line) { }
        };
    }
    @Test public void eofWithoutNewlineAndMultipleHostsAreImported() throws Exception {
        List<String> entries = new ArrayList<>();
        assertEquals(3, BoundedSourceImport.read(new StringReader("0.0.0.0 one.test two.test\n@@||three.test^"), false, true, sink(entries)));
        assertEquals(List.of("one.test", "two.test", "three.test"), entries);
    }
    @Test public void failedReadsAndSinkFailuresReachTheTransaction() throws Exception {
        Reader failing = new StringReader("0.0.0.0 one.test\n") {
            @Override public int read() throws IOException {
                int value = super.read();
                if (value == -1) throw new IOException("download failed after a valid batch");
                return value;
            }
        };
        try { BoundedSourceImport.read(failing, false, true, sink(new ArrayList<>())); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("download failed")); }
        BoundedSourceImport.Sink failingSink = new BoundedSourceImport.Sink() {
            public void entry(DomainSourceParser.Entry e) throws IOException { throw new IOException("storage failure"); }
            public void cosmetic(String line) { }
        };
        try { BoundedSourceImport.read(new StringReader("0.0.0.0 one.test"), false, true, failingSink); fail(); }
        catch (IOException expected) { assertEquals("storage failure", expected.getMessage()); }
    }
    @Test public void inputLimitsNulAndCancellationAreFailures() throws Exception {
        String[] values = {"0.0.0.0\0one.test", "0.0.0.0 bad..test", "x".repeat(20), "0.0.0.0 one.test two.test"};
        for (String value : values) {
            try { BoundedSourceImport.read(new StringReader(value), false, true, sink(new ArrayList<>()), 24, 16, 1); fail(value); }
            catch (IOException expected) { }
        }
        Thread.currentThread().interrupt();
        try { BoundedSourceImport.read(new StringReader(""), false, true, sink(new ArrayList<>())); fail(); }
        catch (IOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
