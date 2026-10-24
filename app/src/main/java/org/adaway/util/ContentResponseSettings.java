package org.adaway.util;

import org.adaway.model.source.AtomicRuleFile;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable settings for HTTP requests already received by the response server. */
public final class ContentResponseSettings {
    public static final String PREFIX = "block_reply_";
    public static final String FILE_NAME = "block_config.json";
    private static final Map<String, String> FIELDS;
    static {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String type : new String[]{"images", "scripts", "styles", "fonts", "media",
                "structures", "api", "telemetry", "config", "ws_sse"}) fields.put(type, "reply_" + type);
        fields.put("visible", "reply_visible");
        fields.put("dedup", "dedup_enabled");
        fields.put("retry_guard", "retry_guard_enabled");
        fields.put("circuit_breaker", "circuit_breaker_enabled");
        fields.put("rule_override", "rule_override_enabled");
        FIELDS = Collections.unmodifiableMap(fields);
    }
    private final Map<String, Boolean> values;
    private ContentResponseSettings(Map<String, Boolean> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    public static ContentResponseSettings fromPreferences(Map<String, ?> preferences) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        FIELDS.forEach((key, field) -> {
            Object value = preferences.get(PREFIX + key);
            values.put(key, value instanceof Boolean ? (Boolean) value : field.startsWith("reply_") && !key.equals("visible"));
        });
        return new ContentResponseSettings(values);
    }
    public Map<String, Boolean> values() { return values; }
    public boolean enabled(String key) {
        if (!FIELDS.containsKey(key)) throw new IllegalArgumentException("Unknown response setting");
        return Boolean.TRUE.equals(values.get(key));
    }
    public ContentResponseSettings with(String key, boolean enabled) {
        enabled(key);
        Map<String, Boolean> copy = new LinkedHashMap<>(values);
        copy.put(key, enabled);
        return new ContentResponseSettings(copy);
    }
    /** Legacy presets change all ten categories, retaining the independent protection options. */
    public ContentResponseSettings withCategories(boolean enabled) {
        Map<String, Boolean> copy = new LinkedHashMap<>(values);
        FIELDS.forEach((key, field) -> {
            if (field.startsWith("reply_") && !key.equals("visible")) copy.put(key, enabled);
        });
        return new ContentResponseSettings(copy);
    }
    public String toJson(String generation) throws IOException {
        if (!validGeneration(generation)) throw new IOException("Invalid configuration generation");
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> field : FIELDS.entrySet())
                json.put(field.getValue(), enabled(field.getKey()) ? 1 : 0);
            json.put("cache_reuse_enabled", 0);
            json.put("generation", generation);
            return json.toString() + "\n";
        } catch (JSONException e) { throw new IOException("Cannot encode response settings", e); }
    }
    /** A saved file is authoritative; damaged files are reported rather than silently overwritten. */
    public static ContentResponseSettings read(File file, Map<String, ?> preferences) throws IOException {
        if (!file.exists()) return fromPreferences(preferences);
        if (Files.size(file.toPath()) > 2047) throw new IOException("Response settings exceed native limit");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
            JSONObject json = new JSONObject(text);
            Map<String, Boolean> values = new LinkedHashMap<>();
            ContentResponseSettings defaults = fromPreferences(Collections.emptyMap());
            for (Map.Entry<String, String> field : FIELDS.entrySet()) {
                Object value = json.opt(field.getValue());
                if (value != null && (!(value instanceof Number) ||
                        (!value.toString().equals("0") && !value.toString().equals("1"))))
                    throw new IOException("Invalid response setting: " + field.getKey());
                values.put(field.getKey(), value == null ? defaults.enabled(field.getKey()) : value.toString().equals("1"));
            }
            return new ContentResponseSettings(values);
        } catch (JSONException e) { throw new IOException("Invalid response settings file", e); }
    }
    public interface Verifier { boolean reload(String generation); }
    public static final class Saved {
        public final ContentResponseSettings settings;
        public final String generation;
        public final boolean confirmed;
        Saved(ContentResponseSettings settings, String generation, boolean confirmed) {
            this.settings = settings; this.generation = generation; this.confirmed = confirmed;
        }
    }
    /** Publication failure preserves the previous complete file and never calls the server. */
    public Saved publish(File file, Verifier verifier) throws IOException {
        String generation = UUID.randomUUID().toString().replace("-", "");
        String json = toJson(generation);
        AtomicRuleFile.write(file, writer -> writer.write(json));
        return new Saved(this, generation, verifier.reload(generation));
    }
    public static boolean validGeneration(String token) { return token != null && token.matches("[a-f\\d]{32}"); }
    @Override public boolean equals(Object other) {
        return other instanceof ContentResponseSettings && values.equals(((ContentResponseSettings) other).values);
    }
    @Override public int hashCode() { return values.hashCode(); }
}
