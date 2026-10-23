package org.adaway.model.update;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real model, package version, preferences and cache on API 30/35; no installation. */
@RunWith(AndroidJUnit4.class)
public class UpdateChannelDeviceTest {
    static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static JSONObject compatibleArtifacts(String tag, boolean preview) throws org.json.JSONException {
        JSONObject artifacts = new JSONObject();
        for (String abi : new String[]{"arm64-v8a", "x86_64", "riscv64"}) {
            String asset = preview ? "AdAway-preview-" + (abi.equals("arm64-v8a") ? "arm64" : abi) + ".apk" :
                    abi.equals("arm64-v8a") ? "ADBlock-latest.apk" : "ADBlock-" + abi + ".apk";
            artifacts.put(abi, new JSONObject().put("downloadUrl", ReleaseFeed.BASE + tag + "/" + asset)
                    .put("packageName", "com.wxcvm.Adaway").put("minSdk", abi.equals("riscv64") ? 35 : 30)
                    .put("sha256", DIGEST));
        }
        return artifacts;
    }

    static String compatibleStable(int code) throws org.json.JSONException {
        return new JSONObject().put("version", "6.8.0.1").put("versionCode", code)
                .put("artifacts", compatibleArtifacts("6.8.0.1", false)).toString();
    }

    @Test public void actualDeviceSdkRejectsUnavailableArtifactsWithoutStartingDownloads() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("update-incompatible-artifact-fixture", Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        JSONObject artifacts = compatibleArtifacts("6.8.0.1", false);
        for (String abi : new String[]{"arm64-v8a", "x86_64", "riscv64"}) {
            artifacts.getJSONObject(abi).put("minSdk", Math.max(35, android.os.Build.VERSION.SDK_INT + 1));
        }
        String document = new JSONObject().put("version", "6.8.0.1").put("versionCode", 2000000000)
                .put("artifacts", artifacts).toString();
        AtomicInteger queued = new AtomicInteger();
        UpdateModel.DownloadBackend downloads = new UpdateModel.DownloadBackend() {
            @Override public UpdateModel.DownloadState state(long id) { return UpdateModel.DownloadState.NONE; }
            @Override public long enqueue(Manifest selected) { queued.incrementAndGet(); return 10; }
            @Override public void cancel(long id) { fail("No download should need cancellation"); }
        };
        try {
            assertTrue(prefs.edit().clear().putBoolean("updateCheckAppDaily", false).commit());
            UpdateModel model = new UpdateModel(isolated, url -> document, downloads);
            assertNull(model.checkForUpdate(true));
            assertNotNull(model.getLastError());
            assertEquals(0, queued.get());
            assertEquals(UpdateModel.DownloadState.NONE, model.getPendingDownloadState());
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }

    @Test public void switchingChannelInvalidatesCacheAndBlocksPendingPreview() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("update-channel-fixture",Context.MODE_PRIVATE);
        Context isolated=new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return prefs; }
        };
        AtomicInteger requests=new AtomicInteger();
        try {
            assertTrue(prefs.edit().clear().putBoolean("updateCheckAppDaily",false).putBoolean("automaticAppUpdate",false).commit());
            String preview=new JSONObject().put("version","6.7.6-preview.21").put("versionCode",2000000000)
                    .put("channel","preview").put("packageName","com.wxcvm.Adaway")
                    .put("tag","progress-android-20261020.5")
                    .put("artifacts",compatibleArtifacts("progress-android-20261020.5",true))
                    .put("downloadUrl",ReleaseFeed.BASE+"progress-android-20261020.5/AdAway-preview-arm64.apk").toString();
            String stable = compatibleStable(1);
            UpdateModel model=new UpdateModel(isolated,url -> {
                requests.incrementAndGet();
                if (ReleaseFeed.STABLE.equals(url)) return stable;
                if (ReleaseFeed.PREVIEW.equals(url)) return preview;
                throw new java.io.IOException("Unexpected network path");
            });
            assertFalse(model.checkForUpdate(false).preview);
            int count=requests.get();
            assertFalse(model.checkForUpdate(false).preview);
            assertEquals(count,requests.get());
            assertTrue(prefs.edit().putBoolean("includeBetaReleases",true).commit());
            Manifest selected = model.checkForUpdate(false);
            assertTrue(selected.preview);
            assertTrue(selected.updateAvailable);
            assertEquals(android.os.Build.SUPPORTED_ABIS[0], selected.nativeAbi);
            assertTrue(selected.downloadUrl.endsWith(android.os.Build.SUPPORTED_ABIS[0].equals("arm64-v8a") ?
                    "/AdAway-preview-arm64.apk" : "/AdAway-preview-" + android.os.Build.SUPPORTED_ABIS[0] + ".apk"));
            assertTrue(prefs.edit().putLong("pending_download_id",42).putLong("expected_apk_version_code",2000000000)
                    .putBoolean("pending_preview",true).commit());
            assertTrue(UpdateModel.pendingDownloadAllowed(isolated,42));
            assertTrue(prefs.edit().putBoolean("includeBetaReleases",false).commit());
            assertFalse(UpdateModel.pendingDownloadAllowed(isolated,42));
            assertFalse(model.checkForUpdate(false).preview);
            assertEquals(-1,model.update());
            UpdateModel restarted=new UpdateModel(isolated,url -> stable);
            assertFalse(restarted.checkForUpdate(false).preview);
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }
}
