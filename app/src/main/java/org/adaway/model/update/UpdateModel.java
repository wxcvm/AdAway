package org.adaway.model.update;

import static org.adaway.model.update.UpdateStore.getApkStore;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.json.JSONException;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/** Application updater. Production-identity previews are an explicit opt-in channel. */
public class UpdateModel {
    private static final String RELEASES_PAGE = "https://github.com/wxcvm/AdAway/releases";
    /**
     * How long a successful check is reused before hitting the API again.
     * The unauthenticated GitHub API is limited to 60 requests per hour per
     * IP, and the check runs on every app start, so results are cached.
     */
    private static final long CHECK_CACHE_DELAY = 30L * 60L * 1000L;

    // Persist the DownloadManager id across app restarts. A dynamically
    // registered receiver cannot be trusted to survive process termination.
    private static final String UPDATE_DOWNLOAD_PREFS = "update_download_state";
    private static final String PENDING_DOWNLOAD_ID = "pending_download_id";
    private static final String PENDING_PREVIEW = "pending_preview";
    private static final String EXPECTED_APK_VERSION_CODE = "expected_apk_version_code";

    static long expectedDownloadedVersionCode(Context context) {
        return context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                .getLong(EXPECTED_APK_VERSION_CODE, -1);
    }

    static boolean pendingDownloadAllowed(Context context, long id) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE);
        return id >= 0 && prefs.getLong(PENDING_DOWNLOAD_ID, -1) == id
                && prefs.getLong(EXPECTED_APK_VERSION_CODE, -1) > 0
                && (!prefs.getBoolean(PENDING_PREVIEW, false) || PreferenceHelper.getIncludeBetaReleases(context));
    }

    public enum DownloadState { NONE, DOWNLOADING, READY_TO_INSTALL, FAILED }

    public DownloadState getPendingDownloadState() {
        long id = this.context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                .getLong(PENDING_DOWNLOAD_ID, -1);
        if (id < 0 || !pendingDownloadAllowed(this.context, id)) return DownloadState.NONE;
        return this.downloads.state(id);
    }

    private DownloadState inspectDownload(long id) {
        DownloadManager dm = this.context.getSystemService(DownloadManager.class);
        if (dm == null) return DownloadState.FAILED;
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return DownloadState.FAILED;
            int col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (col < 0) return DownloadState.FAILED;
            switch (cursor.getInt(col)) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    return DownloadState.READY_TO_INSTALL;
                case DownloadManager.STATUS_FAILED:
                    return DownloadState.FAILED;
                default:
                    return DownloadState.DOWNLOADING;
            }
        } catch (Exception exception) {
            Timber.w(exception, "Unable to inspect pending APK download");
            return DownloadState.FAILED;
        }
    }

    /**
     * Explicit foreground recovery path if the original download-complete
     * broadcast was missed or Android blocked a background installer launch.
     * The same signing/package/version checks are performed again.
     */
    public boolean retryInstallDownloadedUpdate() {
        if (getPendingDownloadState() != DownloadState.READY_TO_INSTALL) return false;
        long id = this.context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                .getLong(PENDING_DOWNLOAD_ID, -1);
        ApkDownloadReceiver.resumeInstall(this.context, id);
        return true;
    }

    public static void clearPendingDownload(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE);
        clearVerifiedCopies(context, prefs.getLong(PENDING_DOWNLOAD_ID, -1));
        prefs.edit().remove(PENDING_DOWNLOAD_ID)
                .remove(EXPECTED_APK_VERSION_CODE).remove(PENDING_PREVIEW).apply();
    }

    private static void clearVerifiedCopies(Context context, long id) {
        clearVerifiedCopiesExcept(context, id, null);
    }

    static void clearVerifiedCopiesExcept(Context context, long id, java.io.File keep) {
        if (id < 0) return;
        java.io.File[] copies = context.getCacheDir().listFiles((dir, name) ->
                name.startsWith("downloaded-update-" + id + "-") && name.endsWith(".apk"));
        if (copies != null) {
            for (java.io.File copy : copies) {
                if (!copy.equals(keep) && !copy.delete()) {
                    Timber.w("Cannot remove completed update cache file");
                }
            }
        }
    }

    private final Context context;
    private final VersionInfo versionInfo;
    private final OkHttpClient client;
    private final ReleaseFeed.Fetcher feedFetcher;
    private final MutableLiveData<Manifest> manifest;
    interface DownloadBackend {
        DownloadState state(long id);
        long enqueue(Manifest selected);
        void cancel(long id);
    }
    private final DownloadBackend downloads;
    private final boolean systemDownloads;
    private Manifest cachedManifest;
    private long lastCheckAt;
    private boolean cachedPreviewChannel;
    private String lastError;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public UpdateModel(Context context) {
        this(context, null);
    }

    UpdateModel(Context context, ReleaseFeed.Fetcher feedFetcher) {
        this(context, feedFetcher, null);
    }

    UpdateModel(Context context, ReleaseFeed.Fetcher feedFetcher, DownloadBackend downloads) {
        this.context = context;
        this.versionInfo = VersionInfo.get(context);
        this.manifest = new MutableLiveData<>();
        this.client = buildHttpClient();
        this.feedFetcher = feedFetcher == null ? this::fetchFeed : feedFetcher;
        this.systemDownloads = downloads == null;
        this.downloads = downloads == null ? new DownloadBackend() {
            @Override public DownloadState state(long id) { return inspectDownload(id); }
            @Override public long enqueue(Manifest selected) { return download(selected); }
            @Override public void cancel(long id) {
                DownloadManager manager = context.getSystemService(DownloadManager.class);
                if (manager != null) manager.remove(id);
            }
        } : downloads;
        ApkUpdateService.syncPreferences(context);
        if (downloads == null && getPendingDownloadState() == DownloadState.READY_TO_INSTALL) {
            long id = context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                    .getLong(PENDING_DOWNLOAD_ID, -1);
            ApkInstallWorker.enqueue(context, id);
        }
    }

    /**
     * Get the current version code.
     *
     * @return The current version code.
     */
    public int getVersionCode() {
        return this.versionInfo.code;
    }

    /**
     * Get the current version name.
     *
     * @return The current version name.
     */
    public String getVersionName() {
        return this.versionInfo.name;
    }

    /**
     * Get the last version manifest.
     *
     * @return The last version manifest.
     */
    public LiveData<Manifest> getManifest() {
        return this.manifest;
    }

    /**
     * Get the releases page of the update feed.
     *
     * @return The releases page URL.
     */
    public static String getReleasesPage() {
        return RELEASES_PAGE;
    }

    /**
     * Get the last check failure reason ({@code null} when the last check succeeded).
     *
     * @return The last error, or {@code null}.
     */
    public String getLastError() {
        return this.lastError;
    }

    /**
     * Get the application update store.
     *
     * @return The application update store.
     */
    public UpdateStore getStore() {
        return getApkStore(this.context);
    }

    /**
     * Get the application update channel.
     *
     * @return The application update channel.
     */
    public String getChannel() {
        return PreferenceHelper.getIncludeBetaReleases(this.context) ? "beta" : "stable";
    }

    /**
     * Check if there is an update available, reusing a recent result when possible.
     */
    public void checkForUpdate() {
        checkForUpdate(false);
    }

    /**
     * Check if there is an update available.
     *
     * @param force {@code true} to bypass the short lived result cache.
     * @return The manifest, or {@code null} if the check failed.
     */
    public synchronized Manifest checkForUpdate(boolean force) {
        boolean previewChannel = PreferenceHelper.getIncludeBetaReleases(this.context);
        if (previewChannel != this.cachedPreviewChannel) {
            this.cachedManifest = null;
            this.manifest.postValue(null);
        }
        if (!force
                && this.cachedManifest != null
                && System.currentTimeMillis() - this.lastCheckAt < CHECK_CACHE_DELAY) {
            this.manifest.postValue(this.cachedManifest);
            prepareAutomaticUpdate(this.cachedManifest);
            return this.cachedManifest;
        }
        Manifest selected = downloadManifest(previewChannel);
        if (previewChannel != PreferenceHelper.getIncludeBetaReleases(this.context)) {
            this.cachedManifest = null;
            this.manifest.postValue(null);
            this.lastError = "Update channel changed; check again";
            return null;
        }
        this.cachedPreviewChannel = previewChannel;
        this.lastCheckAt = System.currentTimeMillis();
        if (selected != null) {
            this.cachedManifest = selected;
            this.lastError = null;
            this.manifest.postValue(selected);
            prepareAutomaticUpdate(selected);
        } else if (this.cachedManifest == null) {
            // Keep the previous result (if any) instead of clearing the UI.
            this.lastError = this.lastError == null ? "unreachable" : this.lastError;
        }
        return selected;
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
    }

    private Manifest downloadManifest(boolean includePreview) {
        if (!this.versionInfo.isValid()) return null;
        try {
            return new ReleaseFeed(this.versionInfo.code, this.feedFetcher).check(includePreview);
        } catch (IOException | JSONException exception) {
            Timber.w(exception, "Unable to check selected update channel");
            this.lastError = exception.getMessage();
            return null;
        }
    }

    private String fetchFeed(String url) throws IOException {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "ADBlock/" + this.versionInfo.name)
                .header("Cache-Control", "no-cache").build();
        try (Response response = this.client.newCall(request).execute();
             ResponseBody body = response.body()) {
            if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
            // Bounded even when an upstream omits Content-Length.
            try (java.io.InputStream input = body.byteStream()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException("Release feed too large");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            }
        }
    }

    /**
     * Update the application to the latest version.
     *
     * @return The download identifier ({@code -1} if download was not started).
     */
    public synchronized long update() {
        // LiveData.postValue may still hold an earlier channel/version on the main thread.
        Manifest selected = this.cachedManifest != null ? this.cachedManifest : this.manifest.getValue();
        if (selected == null || !selected.updateAvailable || selected.versionCode <= this.versionInfo.code
                || (selected.preview && !PreferenceHelper.getIncludeBetaReleases(this.context))) return -1;
        android.content.SharedPreferences prefs = this.context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE);
        long previousId = prefs.getLong(PENDING_DOWNLOAD_ID, -1);
        long previousVersion = prefs.getLong(EXPECTED_APK_VERSION_CODE, -1);
        if (previousId >= 0 && previousVersion == selected.versionCode && pendingDownloadAllowed(this.context, previousId)) {
            DownloadState state = this.downloads.state(previousId);
            if (state == DownloadState.DOWNLOADING || state == DownloadState.READY_TO_INSTALL) return previousId;
        }
        long id = this.downloads.enqueue(selected);
        if (id < 0) return -1;
        // Persist before accepting a completion event, including after process death.
        if (!prefs.edit().putLong(PENDING_DOWNLOAD_ID, id)
                .putLong(EXPECTED_APK_VERSION_CODE, selected.versionCode)
                .putBoolean(PENDING_PREVIEW, selected.preview).commit()) {
            this.downloads.cancel(id);
            return -1;
        }
        if (previousId >= 0 && previousId != id) {
            this.downloads.cancel(previousId);
            clearVerifiedCopies(this.context, previousId);
        }
        // Also covers an immediate completion racing the preference commit.
        if (this.systemDownloads && this.downloads.state(id) == DownloadState.READY_TO_INSTALL)
            ApkInstallWorker.enqueue(this.context, id);
        return id;
    }

    private void prepareAutomaticUpdate(Manifest selected) {
        if (!PreferenceHelper.getAutomaticAppUpdate(this.context) || !selected.updateAvailable) return;
        android.content.SharedPreferences prefs = this.context.getSharedPreferences(UPDATE_DOWNLOAD_PREFS, Context.MODE_PRIVATE);
        // Failed downloads require an explicit retry; polling must not loop downloads forever.
        if (prefs.getLong(EXPECTED_APK_VERSION_CODE, -1) == selected.versionCode &&
                getPendingDownloadState() == DownloadState.FAILED) return;
        try {
            update();
        } catch (RuntimeException exception) {
            Timber.w(exception, "Automatic update download could not be queued");
        }
    }

    private long download(Manifest manifest) {
        String url = manifest.downloadUrl == null || manifest.downloadUrl.isEmpty()
                ? RELEASES_PAGE
                : manifest.downloadUrl;
        Timber.i("Downloading %s from %s.", manifest.version, url);
        Uri uri = Uri.parse(url);
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setMimeType("application/vnd.android.package-archive")
                .setTitle("ADBlock " + manifest.version)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDescription(this.context.getString(R.string.update_notification_description));
        DownloadManager downloadManager = this.context.getSystemService(DownloadManager.class);
        if (downloadManager == null) return -1;
        return downloadManager.enqueue(request);
    }

    private static class VersionInfo {
        private final int code;
        private final String name;

        private VersionInfo(int code, String name) {
            this.code = code;
            this.name = name;
        }

        public static VersionInfo get(Context context) {
            try {
                PackageInfo packageInfo = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                return new VersionInfo(packageInfo.versionCode, packageInfo.versionName);
            } catch (PackageManager.NameNotFoundException e) {
                return new VersionInfo(0, "development");
            }
        }

        public boolean isValid() {
            return this.code > 0;
        }
    }
}
