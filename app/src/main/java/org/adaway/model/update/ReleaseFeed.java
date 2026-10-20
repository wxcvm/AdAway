package org.adaway.model.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Channel selection shared by foreground checks and the periodic worker. */
final class ReleaseFeed {
    static final String BASE = "https://github.com/wxcvm/AdAway/releases/download/";
    static final String STABLE = BASE + "android-latest/manifest.json";
    static final String PREVIEW = BASE + "android-preview/manifest.json";
    static final String API = "https://api.github.com/repos/wxcvm/AdAway/releases?per_page=100";

    interface Fetcher { String get(String url) throws IOException; }

    private final int currentCode;
    private final Fetcher fetcher;

    ReleaseFeed(int currentCode, Fetcher fetcher) {
        this.currentCode = currentCode;
        this.fetcher = fetcher;
    }

    Manifest check(boolean includePreview) throws IOException, JSONException {
        Manifest stable = read(STABLE, false, null);
        Manifest preview = includePreview ? read(PREVIEW, true, null) : null;
        Manifest best = newer(stable, preview);
        if (stable != null && (!includePreview || preview != null)) return best;
        // Missing channel pointers fall back to immutable manifests. A date tag
        // alone cannot establish the versionCode of an installable Android APK.
        JSONArray releases = new JSONArray(fetcher.get(API));
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft")) continue;
            boolean beta = release.optBoolean("prerelease");
            if (beta && !includePreview) continue;
            String tag = release.optString("tag_name");
            if (!hasManifest(release.optJSONArray("assets"))) continue;
            if (!(beta ? tag.matches("progress-android-[0-9]{8}\\.[0-9]+")
                    : tag.matches("v?[0-9]+(?:\\.[0-9]+)+"))) continue;
            Manifest candidate = read(BASE + tag + "/manifest.json", beta, tag);
            best = newer(best, candidate);
        }
        if (best == null) throw new IOException("No valid release manifest");
        return best;
    }

    private static boolean hasManifest(JSONArray assets) {
        if (assets == null) return false;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && "manifest.json".equals(asset.optString("name"))) return true;
        }
        return false;
    }

    private Manifest read(String url, boolean preview, String expectedTag) {
        try {
            return parse(new JSONObject(fetcher.get(url)), preview, expectedTag, currentCode);
        } catch (IOException | JSONException exception) {
            return null;
        }
    }

    static Manifest newer(Manifest left, Manifest right) {
        if (left == null) return right;
        return right != null && right.versionCode > left.versionCode ? right : left;
    }

    static Manifest parse(JSONObject json, boolean preview, String expectedTag, int currentCode) {
        int code = json.optInt("versionCode", -1);
        String version = json.optString("version");
        if (code <= 0 || version.isEmpty()) return null;
        String tag = preview ? json.optString("tag") : version;
        if (expectedTag != null && !expectedTag.equals(tag)) return null;
        String apk = preview ? "AdAway-preview-arm64.apk" : "ADBlock-latest.apk";
        String url = BASE + tag + "/" + apk;
        if (preview && (!tag.matches("progress-android-[0-9]{8}\\.[0-9]+")
                || !"preview".equals(json.optString("channel"))
                || !"com.wxcvm.Adaway".equals(json.optString("packageName"))
                || !url.equals(json.optString("downloadUrl")))) return null;
        if (!preview && !tag.matches("v?[0-9]+(?:\\.[0-9]+)+")) return null;
        return new Manifest(version, code, json.optString("changelog"), url, code > currentCode, preview);
    }
}
