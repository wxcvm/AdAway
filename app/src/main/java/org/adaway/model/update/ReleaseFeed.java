package org.adaway.model.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/** Channel selection shared by foreground checks and the periodic worker. */
final class ReleaseFeed {
    static final String BASE = "https://github.com/wxcvm/AdAway/releases/download/";
    static final String STABLE = BASE + "android-latest/manifest.json";
    static final String PREVIEW = BASE + "android-preview/manifest.json";
    static final String API = "https://api.github.com/repos/wxcvm/AdAway/releases?per_page=100";
    private static final String PREVIEW_TAG = "progress-android-\\d{8}\\.\\d++";
    private static final String STABLE_TAG = "v?\\d++(?:\\.\\d++)++";

    interface Fetcher { String get(String url) throws IOException; }

    private final int currentCode;
    private final Fetcher fetcher;
    private final String[] supportedAbis;
    private final int sdk;

    ReleaseFeed(int currentCode, Fetcher fetcher) {
        this(currentCode, fetcher, new String[]{"arm64-v8a"}, Integer.MAX_VALUE);
    }

    ReleaseFeed(int currentCode, Fetcher fetcher, String[] supportedAbis, int sdk) {
        this.currentCode = currentCode;
        this.fetcher = fetcher;
        this.supportedAbis = supportedAbis == null ? new String[0] : supportedAbis.clone();
        this.sdk = sdk;
    }

    Manifest check(boolean includePreview) throws IOException, JSONException {
        Manifest stable = read(STABLE, false, null);
        Manifest preview = includePreview ? read(PREVIEW, true, null) : null;
        Manifest best = newer(stable, preview);
        if (stable != null && (!includePreview || preview != null)) return best;
        // Missing channel pointers fall back to immutable manifests. A date tag
        // alone cannot establish the versionCode of an installable Android APK.
        JSONArray releases;
        try { releases = new JSONArray(fetcher.get(API)); }
        catch (IOException | JSONException failure) {
            if (best != null) return best;
            throw failure;
        }
        for (int i = 0; i < releases.length(); i++) {
            best = newer(best, fromRelease(releases.optJSONObject(i), includePreview));
        }
        if (best == null) throw new IOException("No valid release manifest");
        return best;
    }

    private Manifest fromRelease(JSONObject release, boolean includePreview) {
        if (release == null || release.optBoolean("draft")) return null;
        boolean beta = release.optBoolean("prerelease");
        if (beta && !includePreview) return null;
        String tag = release.optString("tag_name");
        if (!hasManifest(release.optJSONArray("assets")) || tag.length() > 128
                || !tag.matches(beta ? PREVIEW_TAG : STABLE_TAG)) return null;
        return read(BASE + tag + "/manifest.json", beta, tag);
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
            return parse(new JSONObject(fetcher.get(url)), preview, expectedTag, currentCode, supportedAbis, sdk);
        } catch (IOException | JSONException exception) {
            return null;
        }
    }

    static Manifest newer(Manifest left, Manifest right) {
        if (left == null) return right;
        return right != null && right.versionCode > left.versionCode ? right : left;
    }

    static Manifest parse(JSONObject json, boolean preview, String expectedTag, int currentCode) {
        return parse(json, preview, expectedTag, currentCode, new String[]{"arm64-v8a"}, Integer.MAX_VALUE);
    }

    static Manifest parse(JSONObject json, boolean preview, String expectedTag, int currentCode,
                          String[] supportedAbis, int sdk) {
        if (json == null || supportedAbis == null || supportedAbis.length == 0 || sdk <= 0) return null;
        int code = json.optInt("versionCode", -1);
        String version = json.optString("version");
        if (code <= 0 || version.isEmpty()) return null;
        String tag = preview ? json.optString("tag") : version;
        if (expectedTag != null && !expectedTag.equals(tag)) return null;
        String apk = preview ? "AdAway-preview-arm64.apk" : "ADBlock-latest.apk";
        String url = BASE + tag + "/" + apk;
        if (tag.length() > 128) return null;
        if (preview && (!tag.matches(PREVIEW_TAG)
                || !"preview".equals(json.optString("channel"))
                || !"com.wxcvm.Adaway".equals(json.optString("packageName"))
                || !url.equals(json.optString("downloadUrl")))) return null;
        if (!preview && !tag.matches(STABLE_TAG)) return null;
        if (!json.has("artifacts")) {
            // Legacy pointers describe ARM64 only. Never offer them to x86/RISC-V devices.
            for (String abi : supportedAbis) if ("arm64-v8a".equals(abi)) {
                return new Manifest(version, code, json.optString("changelog"), url, code > currentCode, preview);
            }
            return null;
        }
        JSONObject artifacts = json.optJSONObject("artifacts");
        if (artifacts == null || artifacts.length() == 0 || artifacts.length() > 3) return null;
        Iterator<String> keys = artifacts.keys();
        while (keys.hasNext()) {
            String abi = keys.next();
            JSONObject artifact = artifacts.optJSONObject(abi);
            if (!artifactValid(artifact, abi, tag, preview)) return null;
        }
        for (String abi : supportedAbis) {
            JSONObject artifact = artifacts.optJSONObject(abi == null ? "" : abi);
            if (artifact != null && artifact.optInt("minSdk") <= sdk) {
                return new Manifest(version, code, json.optString("changelog"), artifact.optString("downloadUrl"),
                        code > currentCode, preview, abi, artifact.optString("sha256").toLowerCase(Locale.ROOT),
                        artifact.optInt("minSdk"));
            }
        }
        return null;
    }

    private static boolean artifactValid(JSONObject artifact, String abi, String tag, boolean preview) {
        int floor = "riscv64".equals(abi) ? 35 :
                "arm64-v8a".equals(abi) || "x86_64".equals(abi) ? 30 : -1;
        if (floor < 0 || artifact == null) return false;
        Object minimum = artifact.opt("minSdk");
        if (!(minimum instanceof Integer || minimum instanceof Long)) return false;
        long api = ((Number) minimum).longValue();
        if (api < floor || api > 10000) return false;
        String asset = preview ? "AdAway-preview-" + ("arm64-v8a".equals(abi) ? "arm64" : abi) + ".apk" :
                "arm64-v8a".equals(abi) ? "ADBlock-latest.apk" : "ADBlock-" + abi + ".apk";
        return "com.wxcvm.Adaway".equals(artifact.opt("packageName"))
                && (BASE + tag + "/" + asset).equals(artifact.opt("downloadUrl"))
                && artifact.opt("sha256") instanceof String
                && artifact.optString("sha256").matches("[0-9a-fA-F]{64}")
                && (!artifact.has("nativeAbi") || abi.equals(artifact.opt("nativeAbi")));
    }
}
