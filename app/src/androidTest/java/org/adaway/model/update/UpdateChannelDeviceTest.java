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
                    .put("downloadUrl",ReleaseFeed.BASE+"progress-android-20261020.5/AdAway-preview-arm64.apk").toString();
            UpdateModel model=new UpdateModel(isolated,url -> {
                requests.incrementAndGet();
                if (ReleaseFeed.STABLE.equals(url)) return "{\"version\":\"6.7.5.776\",\"versionCode\":1}";
                if (ReleaseFeed.PREVIEW.equals(url)) return preview;
                throw new java.io.IOException("Unexpected network path");
            });
            assertFalse(model.checkForUpdate(false).preview);
            int count=requests.get();
            assertFalse(model.checkForUpdate(false).preview);
            assertEquals(count,requests.get());
            assertTrue(prefs.edit().putBoolean("includeBetaReleases",true).commit());
            assertTrue(model.checkForUpdate(false).preview);
            assertTrue(model.checkForUpdate(false).updateAvailable);
            assertTrue(prefs.edit().putLong("pending_download_id",42).putLong("expected_apk_version_code",2000000000)
                    .putBoolean("pending_preview",true).commit());
            assertTrue(UpdateModel.pendingDownloadAllowed(isolated,42));
            assertTrue(prefs.edit().putBoolean("includeBetaReleases",false).commit());
            assertFalse(UpdateModel.pendingDownloadAllowed(isolated,42));
            assertFalse(model.checkForUpdate(false).preview);
            assertEquals(-1,model.update());
            UpdateModel restarted=new UpdateModel(isolated,url -> "{\"version\":\"6.7.5.776\",\"versionCode\":1}");
            assertFalse(restarted.checkForUpdate(false).preview);
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }
}
