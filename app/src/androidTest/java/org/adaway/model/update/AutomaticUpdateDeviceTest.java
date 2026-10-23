package org.adaway.model.update;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.adaway.helper.PreferenceHelper;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Production model and persistent preferences; downloads are isolated, never installed. */
public class AutomaticUpdateDeviceTest {
    @Test public void completionReceiverRequiresTheActualDownloadProvidersSignaturePermission() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        android.content.pm.PackageManager packages=app.getPackageManager();
        String permission="android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS";
        android.content.pm.ActivityInfo receiver=packages.getReceiverInfo(
                new android.content.ComponentName(app,ApkDownloadReceiver.class),0);
        assertTrue(receiver.exported);
        assertEquals(permission,receiver.permission);
        android.content.pm.ProviderInfo provider=packages.resolveContentProvider("downloads",0);
        assertNotNull(provider);
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                packages.checkPermission(permission,provider.packageName));
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                packages.checkPermission(permission,app.getPackageName()));
        android.content.pm.PermissionInfo definition=packages.getPermissionInfo(permission,0);
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE,
                definition.protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE);
    }
    @Test public void verifiedCacheCleanupIsScopedAndRetainsOnlyChosenCopy() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        java.io.File directory=new java.io.File(app.getCacheDir(),"verified-update-cache-fixture");
        assertTrue(directory.mkdir() || directory.isDirectory());
        Context isolated=new ContextWrapper(app) {
            @Override public java.io.File getCacheDir() { return directory; }
        };
        java.io.File old=new java.io.File(directory,"downloaded-update-42-old.apk");
        java.io.File keep=new java.io.File(directory,"downloaded-update-42-keep.apk");
        java.io.File other=new java.io.File(directory,"downloaded-update-142-other.apk");
        try {
            assertTrue(old.createNewFile()); assertTrue(keep.createNewFile()); assertTrue(other.createNewFile());
            UpdateModel.clearVerifiedCopiesExcept(isolated,42,keep);
            assertFalse(old.exists()); assertTrue(keep.exists()); assertTrue(other.exists());
            UpdateModel.clearVerifiedCopiesExcept(isolated,42,null);
            assertFalse(keep.exists()); assertTrue(other.exists());
        } finally {
            if(old.exists()) assertTrue(old.delete());
            if(keep.exists()) assertTrue(keep.delete());
            if(other.exists()) assertTrue(other.delete());
            assertTrue(directory.delete());
        }
    }
    private static final class Queue implements UpdateModel.DownloadBackend {
        int enqueued, cancelled;
        UpdateModel.DownloadState state = UpdateModel.DownloadState.DOWNLOADING;
        @Override public UpdateModel.DownloadState state(long id) { return state; }
        @Override public long enqueue(Manifest selected) { return 100L + ++enqueued; }
        @Override public void cancel(long id) { cancelled++; }
    }

    @Test public void automaticDownloadsSurviveRestartDeduplicateAndLeaveFailedAttemptsRetryable() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("automatic-update-fixture", Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        Queue queue = new Queue();
        AtomicInteger nextCode = new AtomicInteger(1900000000);
        ReleaseFeed.Fetcher feed = url -> {
            try { return UpdateChannelDeviceTest.compatibleStable(nextCode.get()); }
            catch (org.json.JSONException failure) { throw new java.io.IOException(failure); }
        };
        try {
            assertTrue(prefs.edit().clear().putBoolean("updateCheckAppDaily", false).commit());
            assertTrue(PreferenceHelper.getAutomaticAppUpdate(isolated));
            UpdateModel first = new UpdateModel(isolated, feed, queue);
            assertTrue(first.checkForUpdate(false).updateAvailable);
            assertEquals(1, queue.enqueued);
            assertEquals(android.os.Build.SUPPORTED_ABIS[0], UpdateModel.expectedDownloadedAbi(isolated));
            assertEquals(UpdateChannelDeviceTest.DIGEST, UpdateModel.expectedDownloadedDigest(isolated));
            assertEquals(android.os.Build.SUPPORTED_ABIS[0].equals("riscv64") ? 35 : 30,
                    UpdateModel.expectedDownloadedMinSdk(isolated));
            assertEquals(101L, first.update());
            assertEquals(1, queue.enqueued);
            UpdateModel restarted = new UpdateModel(isolated, feed, queue);
            restarted.checkForUpdate(false);
            assertEquals(1, queue.enqueued);
            queue.state = UpdateModel.DownloadState.READY_TO_INSTALL;
            restarted.checkForUpdate(true);
            assertEquals(1, queue.enqueued);
            queue.state = UpdateModel.DownloadState.FAILED;
            restarted.checkForUpdate(true);
            assertEquals(1, queue.enqueued);
            assertEquals(102L, restarted.update()); // Explicit retry works.
            assertEquals(2, queue.enqueued);
            assertEquals(1, queue.cancelled);
            assertTrue(prefs.edit().putBoolean("automaticAppUpdate", false).commit());
            nextCode.incrementAndGet();
            restarted.checkForUpdate(true);
            assertEquals(2, queue.enqueued);
            assertTrue(prefs.edit().putBoolean("automaticAppUpdate", true).commit());
            queue.state = UpdateModel.DownloadState.DOWNLOADING;
            restarted.checkForUpdate(true);
            assertEquals(3, queue.enqueued);
            assertEquals(2, queue.cancelled);
            assertEquals(nextCode.get(), UpdateModel.expectedDownloadedVersionCode(isolated));
            UpdateModel.clearPendingDownload(isolated);
            assertEquals(UpdateModel.DownloadState.NONE, restarted.getPendingDownloadState());
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }
}
