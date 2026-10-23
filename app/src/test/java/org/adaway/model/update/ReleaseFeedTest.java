package org.adaway.model.update;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ReleaseFeedTest {
    private final Map<String, String> documents = new HashMap<>();
    private ReleaseFeed feed(int installed) {
        return new ReleaseFeed(installed, url -> {
            if (!documents.containsKey(url)) throw new IOException("HTTP 404");
            return documents.get(url);
        });
    }
    private String stable(int code) { return "{\"version\":\"6.7.5.776\",\"versionCode\":"+code+"}"; }
    private String preview(String tag, int code) throws Exception {
        return new JSONObject().put("tag", tag).put("version", "6.7.6-preview.21")
                .put("versionCode", code).put("packageName", "com.wxcvm.Adaway")
                .put("channel", "preview").put("downloadUrl", ReleaseFeed.BASE+tag+"/AdAway-preview-arm64.apk").toString();
    }
    @Test public void stableOnlyNeverQueriesPreview() throws Exception {
        documents.put(ReleaseFeed.STABLE, stable(100776));
        assertFalse(feed(214654692).check(false).updateAvailable);
        assertFalse(feed(100000).check(false).preview);
    }
    @Test public void stableCurrentDoesNotSuppressOptInPreview() throws Exception {
        documents.put(ReleaseFeed.STABLE, stable(100776));
        documents.put(ReleaseFeed.PREVIEW, preview("progress-android-20261020.5",214655000));
        Manifest selected=feed(214654692).check(true);
        assertTrue(selected.updateAvailable);
        assertTrue(selected.preview);
        assertTrue(selected.downloadUrl.endsWith("progress-android-20261020.5/AdAway-preview-arm64.apk"));
        assertFalse(feed(214655000).check(true).updateAvailable);
        assertFalse(feed(214655001).check(true).updateAvailable);
        assertFalse(feed(214654692).check(false).updateAvailable);
    }
    @Test public void newerStableWinsOverPreview() throws Exception {
        documents.put(ReleaseFeed.STABLE, stable(214660000));
        documents.put(ReleaseFeed.PREVIEW, preview("progress-android-20261020.5",214655000));
        assertFalse(feed(214654692).check(true).preview);
    }
    @Test public void fallbackUsesVersionCodesAndSkipsDraftsAndParallelBuilds() throws Exception {
        String prefix="{\"assets\":[{\"name\":\"manifest.json\"}],\"tag_name\":\"";
        documents.put(ReleaseFeed.API,"["+prefix+"progress-android-20261020.99\",\"prerelease\":true},"
                +prefix+"progress-android-20261020.1\",\"prerelease\":true},"
                +prefix+"progress-android-20261020.2\",\"prerelease\":true,\"draft\":true},"
                +prefix+"parallel-preview\",\"prerelease\":true}]");
        documents.put(ReleaseFeed.BASE+"progress-android-20261020.99/manifest.json", preview("progress-android-20261020.99",200));
        documents.put(ReleaseFeed.BASE+"progress-android-20261020.1/manifest.json", preview("progress-android-20261020.1",300));
        documents.put(ReleaseFeed.BASE+"progress-android-20261020.2/manifest.json", preview("progress-android-20261020.2",999));
        assertEquals(300,feed(250).check(true).versionCode);
    }
    @Test public void rejectsForeignPackageUrlAndUnknownIdentity() throws Exception {
        JSONObject json=new JSONObject(preview("progress-android-20261020.5",214655000));
        assertNotNull(ReleaseFeed.parse(json,true,null,1));
        json.put("packageName","com.wxcvm.Adaway.preview");
        assertNull(ReleaseFeed.parse(json,true,null,1));
        json.put("packageName","com.wxcvm.Adaway").put("downloadUrl",ReleaseFeed.BASE+"android-preview/AdAway-preview-arm64.apk");
        assertNull(ReleaseFeed.parse(json,true,null,1));
        assertNull(ReleaseFeed.parse(new JSONObject("{\"version\":\"progress-android-20261020.99\"}"),true,null,1));
    }
    @Test(expected=IOException.class) public void unavailableChannelIsFailure() throws Exception { feed(1).check(true); }

    private JSONObject artifacts(String tag, boolean beta) throws Exception {
        JSONObject result = new JSONObject();
        for (String abi : new String[]{"arm64-v8a", "x86_64", "riscv64"}) {
            String filename = beta ? "AdAway-preview-" + (abi.equals("arm64-v8a") ? "arm64" : abi) + ".apk" :
                    abi.equals("arm64-v8a") ? "ADBlock-latest.apk" : "ADBlock-" + abi + ".apk";
            result.put(abi, new JSONObject().put("downloadUrl", ReleaseFeed.BASE + tag + "/" + filename)
                    .put("packageName", "com.wxcvm.Adaway").put("minSdk", abi.equals("riscv64") ? 35 : 30)
                    .put("sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                    .put("nativeAbi", abi).put("sourceCommit", "metadata is permitted"));
        }
        return result;
    }

    private JSONObject multiAbiPreview() throws Exception {
        String tag = "progress-android-20261023.1";
        return new JSONObject(preview(tag, 300)).put("artifacts", artifacts(tag, true));
    }

    @Test public void choosesEachAndroidAbiInDevicePreferenceOrderAndKeepsLegacyArmEntry() throws Exception {
        JSONObject json = multiAbiPreview();
        for (String abi : new String[]{"arm64-v8a", "x86_64", "riscv64"}) {
            Manifest selected = ReleaseFeed.parse(json, true, null, 1, new String[]{abi}, 35);
            assertNotNull(selected); assertEquals(abi, selected.nativeAbi);
            assertEquals(abi.equals("riscv64") ? 35 : 30, selected.minSdk);
            assertEquals(64, selected.sha256.length());
            assertEquals(json.getJSONObject("artifacts").getJSONObject(abi).getString("downloadUrl"), selected.downloadUrl);
        }
        assertEquals("x86_64", ReleaseFeed.parse(json, true, null, 1,
                new String[]{"unsupported", "x86_64", "arm64-v8a"}, 35).nativeAbi);
        assertTrue(ReleaseFeed.parse(json, true, null, 1).downloadUrl.endsWith("AdAway-preview-arm64.apk"));
    }

    @Test public void legacyArmPointersNeverBecomeX86RiscvOrLinuxPackages() throws Exception {
        JSONObject json = new JSONObject(preview("progress-android-20261020.5", 300));
        assertNotNull(ReleaseFeed.parse(json, true, null, 1, new String[]{"arm64-v8a"}, 30));
        for (String abi : new String[]{"x86_64", "riscv64", "linux-riscv64", "armeabi-v7a"}) {
            assertNull(ReleaseFeed.parse(json, true, null, 1, new String[]{abi}, 35));
            assertNull(ReleaseFeed.parse(new JSONObject(stable(300)), false, null, 1, new String[]{abi}, 35));
        }
        assertNull(ReleaseFeed.parse(multiAbiPreview(), true, null, 1, new String[]{"linux-riscv64"}, 35));
    }

    @Test public void rejectsUnavailableSdkAndMalformedArtifactContractWithoutLegacyFallback() throws Exception {
        assertNull(ReleaseFeed.parse(multiAbiPreview(), true, null, 1, new String[]{"riscv64"}, 34));
        assertNull(ReleaseFeed.parse(multiAbiPreview(), true, null, 1, new String[]{"x86_64"}, 29));
        String[] keys = {"downloadUrl", "downloadUrl", "sha256", "packageName", "minSdk", "minSdk", "nativeAbi"};
        Object[] invalid = {ReleaseFeed.BASE + "android-preview/AdAway-preview-riscv64.apk",
                ReleaseFeed.BASE + "progress-android-20261023.1/AdAway-preview-arm64.apk",
                "bad", "com.wxcvm.Adaway.preview", "35", 34, "arm64-v8a"};
        for (int i = 0; i < keys.length; i++) {
            JSONObject json = multiAbiPreview();
            json.getJSONObject("artifacts").getJSONObject("riscv64").put(keys[i], invalid[i]);
            assertNull(keys[i] + ": " + invalid[i], ReleaseFeed.parse(json, true, null, 1, new String[]{"riscv64"}, 35));
            assertNull("Malformed metadata cannot fall back to top-level ARM64", ReleaseFeed.parse(json, true, null, 1));
        }
        assertNull(ReleaseFeed.parse(multiAbiPreview().put("artifacts", JSONObject.NULL), true, null, 1));
        assertNull(ReleaseFeed.parse(multiAbiPreview().put("artifacts", new JSONObject()), true, null, 1));
        JSONObject unknown = multiAbiPreview();
        unknown.getJSONObject("artifacts").put("linux-riscv64", new JSONObject());
        assertNull(ReleaseFeed.parse(unknown, true, null, 1));
    }

    @Test public void compatiblePreviewSurvivesUnavailableLegacyStableAndApiOnX86() throws Exception {
        documents.put(ReleaseFeed.STABLE, stable(100));
        documents.put(ReleaseFeed.PREVIEW, multiAbiPreview().toString());
        ReleaseFeed feed = new ReleaseFeed(200, url -> {
            if (!documents.containsKey(url)) throw new IOException("unavailable");
            return documents.get(url);
        }, new String[]{"x86_64"}, 30);
        Manifest selected = feed.check(true);
        assertTrue(selected.preview); assertTrue(selected.updateAvailable);
        assertEquals("x86_64", selected.nativeAbi);
        assertThrows(IOException.class, () -> feed.check(false));
    }

    @Test public void futureStableArtifactSelectionKeepsImmutableUrlAndUppercaseDigestIsNormalized() throws Exception {
        JSONObject json = new JSONObject(stable(300)).put("artifacts", artifacts("6.7.5.776", false));
        json.getJSONObject("artifacts").getJSONObject("riscv64").put("sha256",
                "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789");
        Manifest selected = ReleaseFeed.parse(json, false, "6.7.5.776", 301, new String[]{"riscv64"}, 35);
        assertFalse(selected.updateAvailable); assertFalse(selected.preview);
        assertTrue(selected.downloadUrl.endsWith("/6.7.5.776/ADBlock-riscv64.apk"));
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", selected.sha256);
    }
}
