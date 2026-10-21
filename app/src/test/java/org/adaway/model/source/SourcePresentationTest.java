package org.adaway.model.source;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourcePresentationTest {
    @Test public void repairsRawLabelsWithRepositoryAndFile() {
        assertEquals("owner/list · hosts", SourcePresentation.legacyName("raw",
                "https://raw.githubusercontent.com/owner/list/main/hosts?token=secret#private"));
        assertEquals("owner/list · hosts", SourcePresentation.name("",
                "https://github.com/owner/list/raw/refs/heads/main/hosts"));
        assertEquals("My rules", SourcePresentation.name("My rules", "https://example.test/hosts"));
        assertEquals("example.test · hosts", SourcePresentation.legacyName("example", "https://example.test/hosts"));
        assertEquals("raw", SourcePresentation.name("raw", "https://raw.githubusercontent.com/owner/list/main/hosts"));
    }
    @Test public void presentationNeverIncludesUserInfoQueryOrFragment() {
        String address = "https://user:password@example.test:8443/hosts?token=secret#private";
        assertEquals("https://example.test:8443/hosts", SourcePresentation.address(address));
        assertEquals("example.test · hosts", SourcePresentation.name("", address));
        assertEquals("Local document", SourcePresentation.address("content://provider/private-document-id"));
        assertEquals("Invalid source address", SourcePresentation.address("not an address"));
    }
}
