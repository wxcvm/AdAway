package org.adaway.util;

import org.adaway.model.source.AtomicRuleFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.util.Locale;

/** Strict editor for the existing first-match, domain-and-subdomain response overrides. */
public final class ContentResponseRules {
    public static final String FILE_NAME = "rule_overrides.txt";
    public static final int MAX_CHARACTERS = 9000;
    private ContentResponseRules() { }
    public static String validate(String input) throws IOException {
        if (input == null || input.length() > MAX_CHARACTERS) throw new IOException("Rule text exceeds limit");
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (String line : input.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (++count > 64) throw new IOException("At most 64 domain response rules");
            output.append(validateLine(trimmed)).append('\n');
        }
        return output.toString();
    }
    private static String validateLine(String line) throws IOException {
        String[] pair = line.split("=", -1);
        if (pair.length != 2) throw new IOException("Expected domain = status");
        String domain = pair[0].trim().toLowerCase(Locale.ROOT);
        validateDomain(domain);
        String status = pair[1].trim();
        if (!status.matches("204|403|404|410|503|200")) throw new IOException("Unsupported response status");
        return domain + " = " + status;
    }
    private static void validateDomain(String domain) throws IOException {
        if (domain.length() < 4 || domain.length() >= 128 || domain.indexOf('.') < 0)
            throw new IOException("Use a complete domain with at least two labels");
        for (String label : domain.split("\\.", -1)) {
            if (label.length() > 63 || !label.matches("[a-z\\d](?:[a-z\\d-]*[a-z\\d])?"))
                throw new IOException("Use plain ASCII domain labels; paths and filter syntax are unsupported");
        }
    }
    public static String read(File file) throws IOException {
        if (!file.exists()) return "";
        if (Files.size(file.toPath()) > MAX_CHARACTERS) throw new IOException("Rule text exceeds editor limit");
        // Preserve legacy text for explicit correction; never normalize unsupported syntax silently.
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
    }
    public static String save(File file, String input) throws IOException {
        String canonical = validate(input);
        AtomicRuleFile.write(file, writer -> writer.write(canonical));
        return canonical;
    }
}
