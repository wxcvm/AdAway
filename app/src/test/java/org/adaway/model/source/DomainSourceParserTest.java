package org.adaway.model.source;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class DomainSourceParserTest {
    @Test public void existingInternationalizedHostsAreNormalizedWithoutNetworkAccess() {
        assertEquals("xn--bcher-kva.test", DomainSourceParser.parse("0.0.0.0 bücher.test", false, true).entries.get(0).domain);
    }
    @Test public void realParserMatchesSharedNativeFixtures() throws Exception {
        Path path = Path.of("../webserver/jni/source_loader_vectors.txt");
        if (!Files.exists(path)) path = Path.of("webserver/jni/source_loader_vectors.txt");
        int count = 0;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            int flags = Integer.parseInt(cells[0]);
            String status, entries = "";
            try {
                DomainSourceParser.Parsed parsed = DomainSourceParser.parse(cells[1], (flags & 1) != 0, (flags & 2) != 0);
                status = parsed.unsupported ? "UNSUPPORTED" : parsed.cosmetic ? "COSMETIC" : "OK";
                entries = parsed.entries.stream().map(e -> e.kind + ":" + e.domain + ":" + (e.address == null ? "" : e.address))
                        .collect(Collectors.joining(";"));
            } catch (IllegalArgumentException e) { status = "INVALID"; }
            assertEquals(cells[1], cells[2], status);
            if (!status.equals("INVALID")) assertEquals(cells[1], cells[3].equals("-") ? "" : cells[3], entries);
            count++;
        }
        assertTrue(count >= 25);
    }
}
