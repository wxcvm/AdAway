package org.adaway.util;

import org.adaway.model.source.AtomicRuleFile;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ContentResponseSettingsTest {
    @Test public void legacyDefaultsAndKeysRemainStableAndCacheCannotReturn() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("block_reply_images", false);
        prefs.put("block_reply_dedup", true);
        prefs.put("block_reply_cache_reuse", true);
        ContentResponseSettings settings = ContentResponseSettings.fromPreferences(prefs);
        assertEquals(15, settings.values().size());
        assertFalse(settings.enabled("images"));
        assertTrue(settings.enabled("scripts"));
        assertTrue(settings.enabled("dedup"));
        assertFalse(settings.enabled("visible"));
        assertFalse(settings.enabled("rule_override"));
        JSONObject json = new JSONObject(settings.toJson("0123456789abcdef0123456789abcdef"));
        assertEquals(0, json.getInt("cache_reuse_enabled"));
        assertEquals(1, json.getInt("reply_ws_sse"));
        assertEquals(0, json.getInt("reply_images"));
    }

    @Test public void publicationAndRuntimeAcknowledgementAreSeparateAndFileSurvivesRestart() throws Exception {
        File directory = Files.createTempDirectory("content-response").toFile();
        File file = new File(directory, ContentResponseSettings.FILE_NAME);
        try {
            ContentResponseSettings settings = ContentResponseSettings.fromPreferences(Collections.emptyMap()).with("fonts", false);
            ContentResponseSettings.Saved first = settings.publish(file, generation -> {
                assertTrue(ContentResponseSettings.validGeneration(generation));
                try { assertEquals(generation, new JSONObject(read(file)).getString("generation")); }
                catch (Exception error) { throw new AssertionError(error); }
                return false;
            });
            assertFalse(first.confirmed);
            assertEquals(settings, ContentResponseSettings.read(file, Collections.singletonMap("block_reply_fonts", true)));
            ContentResponseSettings.Saved second = settings.publish(file, generation -> true);
            assertTrue(second.confirmed);
            assertNotEquals(first.generation, second.generation);
        } finally { Files.deleteIfExists(file.toPath()); Files.delete(directory.toPath()); }
    }

    @Test public void legacyPresetIncludesConfigAndDoesNotResetProtectionOptions() {
        ContentResponseSettings settings = ContentResponseSettings.fromPreferences(Collections.emptyMap())
                .with("visible", true).with("retry_guard", true).withCategories(false);
        for (String key : new String[]{"images", "scripts", "styles", "fonts", "media", "structures", "api", "telemetry", "config", "ws_sse"})
            assertFalse(key, settings.enabled(key));
        assertTrue(settings.enabled("visible"));
        assertTrue(settings.enabled("retry_guard"));
        assertTrue(settings.withCategories(true).enabled("config"));
    }

    @Test public void writerAndRenameFailuresPreserveLastFileAndNeverClaimApplied() throws Exception {
        File directory = Files.createTempDirectory("content-failure").toFile();
        File file = new File(directory, ContentResponseSettings.FILE_NAME);
        try {
            Files.write(file.toPath(), "last-valid".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> AtomicRuleFile.write(file, writer -> {
                writer.write("incomplete"); throw new IOException("disk failure");
            }));
            assertEquals("last-valid", read(file));
            Files.delete(file.toPath());
            assertTrue(file.mkdir());
            File marker = new File(file, "old");
            Files.write(marker.toPath(), new byte[]{42});
            AtomicBoolean verified = new AtomicBoolean();
            assertThrows(IOException.class, () -> ContentResponseSettings.fromPreferences(Collections.emptyMap()).publish(file, generation -> {
                verified.set(true); return true;
            }));
            assertFalse(verified.get());
            assertEquals(42, Files.readAllBytes(marker.toPath())[0]);
            Files.delete(marker.toPath());
        } finally { Files.deleteIfExists(file.toPath()); Files.delete(directory.toPath()); }
    }

    @Test public void corruptSavedDataIsReportedAndNeverReplacedByPreferenceDefaults() throws Exception {
        File file = File.createTempFile("content-corrupt", ".json");
        try {
            for (String invalid : new String[]{"{", "{\"reply_images\":2}", "{\"reply_images\":true}"}) {
                Files.write(file.toPath(), invalid.getBytes(StandardCharsets.UTF_8));
                assertThrows(IOException.class, () -> ContentResponseSettings.read(file, Collections.emptyMap()));
                assertEquals(invalid, read(file));
            }
        } finally { Files.delete(file.toPath()); }
    }

    @Test public void ruleEditorAcceptsOnlyNativeSupportedWholeDomainsAndStatusCodes() throws Exception {
        assertEquals("example.com = 204\nsub.example.com = 200\n", ContentResponseRules.validate(" Example.COM = 204\r\nsub.example.com=200\n"));
        for (String invalid : new String[]{"example.com/path=204", "*.example.com=204", "||example.com^=204",
                "example.com$script=204", "https://example.com=204", "例子.com=204", "com=204", "a.b=204",
                "example.com.=204", "-a.example=204", "example..com=204", "example.com=201", "example.com=204=403"}) {
            assertThrows(invalid, IOException.class, () -> ContentResponseRules.validate(invalid));
        }
        StringBuilder rules = new StringBuilder();
        for (int index = 0; index < 64; index++) rules.append("host").append(index).append(".example = 403\n");
        assertEquals(64, ContentResponseRules.validate(rules.toString()).split("\n").length);
        assertThrows(IOException.class, () -> ContentResponseRules.validate(rules + "last.example=204"));
        assertThrows(IOException.class, () -> ContentResponseRules.validate("a".repeat(64) + ".example=204"));
    }

    @Test public void invalidRuleEditCannotReplacePreviousPublishedRules() throws Exception {
        File file = File.createTempFile("content-rules", ".txt");
        try {
            ContentResponseRules.save(file, "example.com=404");
            assertThrows(IOException.class, () -> ContentResponseRules.save(file, "example.com/path=204"));
            assertEquals("example.com = 404\n", ContentResponseRules.read(file));
        } finally { Files.delete(file.toPath()); }
    }
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
