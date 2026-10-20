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
}
