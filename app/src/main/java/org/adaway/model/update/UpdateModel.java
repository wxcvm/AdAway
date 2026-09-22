package org.adaway.model.update;

import static android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE;
import static org.adaway.model.update.UpdateStore.getApkStore;

import android.app.DownloadManager;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * This class is the model in charge of updating the application.
 *
 * <p>The update feed is the GitHub Releases list of <b>this repository</b>
 * (wxcvm/AdAway): the newest published release carrying an APK asset is offered
 * for install. When such a release also ships a <code>manifest.json</code>
 * asset, its explicit version code is authoritative; otherwise the tag name is
 * compared with the running version name, so a release published by hand (APK
 * only) still works.</p>
 *
 * <p>The feed used to be a separate repository (wxcvm/Doh-ECH) because this one
 * was private; publishing there needed a personal access token, and every CI
 * run that did not have it either failed or silently stopped updating the feed.
 * The repository is public now, so CI publishes here with its own
 * <code>GITHUB_TOKEN</code> and no secret is involved. Releases without an APK
 * asset - the Windows packages of the same repository - are skipped, and the
 * ranking is by tag build number, so mixing both kinds in one release list is
 * safe.</p>
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class UpdateModel {
    /**
     * The GitHub repository hosting the release feed (owner/name).
     */
    private static final String RELEASES_REPO = "wxcvm/AdAway";
    /**
     * The GitHub releases API endpoint (newest first, drafts excluded by GitHub).
     */
    private static final String RELEASES_API = "https://api.github.com/repos/" + RELEASES_REPO + "/releases?per_page=30";
    /**
     * The human readable releases page, used as manual fallback link.
     */
    private static final String RELEASES_PAGE = "https://github.com/" + RELEASES_REPO + "/releases";
    /**
     * The optional release asset carrying an explicit version code.
     */
    private static final String MANIFEST_ASSET_NAME = "manifest.json";
    /**
     * The fixed-tag feed: one release whose two assets
     * (<code>ADBlock-latest.apk</code> and <code>manifest.json</code>) are
     * overwritten on every build, so the newest version can be read with a plain
     * HTTPS GET.
     *
     * <p>Why this exists: the anonymous GitHub API allows 60 requests per hour
     * per IP address. Behind an exported address (a proxy, an office NAT) that
     * quota is usually already spent by other people, and the check then failed
     * with "HTTP 403" while the network itself was perfectly fine - the app
     * reported "update check failed" and no device ever saw a new build. The
     * fixed tag is served as a normal download, so no rate limit is involved.</p>
     */
    private static final String LATEST_TAG = "android-latest";
    /**
     * URL of the fixed-tag manifest.
     */
    private static final String LATEST_MANIFEST_URL =
            "https://github.com/wxcvm/AdAway/releases/download/" + LATEST_TAG + "/manifest.json";
    /**
     * URL of the fixed-tag APK (same asset name in every build).
     */
    private static final String LATEST_APK_URL =
            "https://github.com/wxcvm/AdAway/releases/download/" + LATEST_TAG + "/ADBlock-latest.apk";
    /**
     * How long a successful check is reused before hitting the API again.
     * The unauthenticated GitHub API is limited to 60 requests per hour per
     * IP, and the check runs on every app start, so results are cached.
     */
    private static final long CHECK_CACHE_DELAY = 30L * 60L * 1000L;

    private final Context context;
    private final VersionInfo versionInfo;
    private final OkHttpClient client;
    private final MutableLiveData<Manifest> manifest;
    private ApkDownloadReceiver receiver;
    private Manifest cachedManifest;
    private long lastCheckAt;
    private String lastError;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public UpdateModel(Context context) {
        this.context = context;
        this.versionInfo = VersionInfo.get(context);
        this.manifest = new MutableLiveData<>();
        this.client = buildHttpClient();
        ApkUpdateService.syncPreferences(context);
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
    public Manifest checkForUpdate(boolean force) {
        if (!force
                && this.cachedManifest != null
                && System.currentTimeMillis() - this.lastCheckAt < CHECK_CACHE_DELAY) {
            this.manifest.postValue(this.cachedManifest);
            return this.cachedManifest;
        }
        Manifest manifest = downloadManifest();
        this.lastCheckAt = System.currentTimeMillis();
        if (manifest != null) {
            this.cachedManifest = manifest;
            this.lastError = null;
            this.manifest.postValue(manifest);
        } else if (this.cachedManifest == null) {
            // Keep the previous result (if any) instead of clearing the UI.
            this.lastError = this.lastError == null ? "unreachable" : this.lastError;
        }
        return manifest;
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder().build();
    }

    private Manifest downloadManifest() {
        if (!this.versionInfo.isValid()) {
            return null;
        }
        // 1) fixed tag: one small GET, no REST API, therefore no rate limit.
        Manifest fixedTag = downloadFixedTagManifest();
        if (fixedTag != null) {
            return fixedTag;
        }
        // 2) REST API listing (60 requests/hour); kept as the fallback for the
        //    builds published before the fixed tag existed.
        Request request = new Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ADBlock/" + this.versionInfo.name)
                .build();
        try (Response response = this.client.newCall(request).execute();
             ResponseBody body = response.body()) {
            if (!response.isSuccessful() || body == null) {
                Timber.w("Update check failed with HTTP %s.", response.code());
                this.lastError = response.code() == 403
                        ? "HTTP 403（GitHub 接口限流，稍后自动重试）"
                        : "HTTP " + response.code();
                return null;
            }
            return parseReleases(new JSONArray(body.string()));
        } catch (IOException | JSONException exception) {
            Timber.e(exception, "Unable to download the release list.");
            this.lastError = exception.getClass().getSimpleName();
            return null;
        }
    }

    /**
     * Read the fixed-tag manifest, which carries the explicit version code of
     * the newest build.
     *
     * @return The manifest, or {@code null} when the fixed tag is unavailable
     *         (offline, or a build published before the tag existed).
     */
    private Manifest downloadFixedTagManifest() {
        Request request = new Request.Builder()
                .url(LATEST_MANIFEST_URL)
                .header("User-Agent", "ADBlock/" + this.versionInfo.name)
                .build();
        try (Response response = this.client.newCall(request).execute();
             ResponseBody body = response.body()) {
            if (!response.isSuccessful() || body == null) {
                Timber.w("Fixed-tag manifest check failed with HTTP %s.", response.code());
                return null;
            }
            JSONObject json = new JSONObject(body.string());
            int versionCode = json.optInt("versionCode", -1);
            String version = json.optString("version", "");
            String changelog = json.optString("changelog", "");
            if (versionCode <= 0 || version.isEmpty()) {
                Timber.w("Fixed-tag manifest is missing version/versionCode.");
                return null;
            }
            boolean updateAvailable = versionCode > this.versionInfo.code;
            return new Manifest(version, versionCode, changelog, LATEST_APK_URL,
                    updateAvailable);
        } catch (IOException | JSONException exception) {
            Timber.w(exception, "Unable to read the fixed-tag manifest.");
            return null;
        }
    }

    /**
     * Pick the newest published release shipping an APK and turn it into a manifest.
     */
    private Manifest parseReleases(JSONArray releases) throws JSONException {
        JSONObject best = null;
        long bestBuild = Long.MIN_VALUE;
        String bestDate = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", false)) {
                continue;
            }
            if (findApkAsset(release) == null) {
                continue;
            }
            // The API order is a creation-date order and creation dates are NOT a
            // reliable build order: CI releases recreated in bulk share one
            // timestamp, which used to make an older APK look "newest". Rank by
            // the build number in the tag instead - CI publishes
            // "<versionName>.<run_number>" with versionCode 100000 + run_number -
            // and only fall back to the date for hand published non numeric tags.
            long build = tagBuildNumber(release.optString("tag_name", ""));
            String date = release.optString("published_at", release.optString("created_at", ""));
            if (best == null || build > bestBuild
                    || (build == bestBuild && date.compareTo(bestDate) > 0)) {
                best = release;
                bestBuild = build;
                bestDate = date;
            }
        }
        if (best == null) {
            this.lastError = "no-release";
            return null;
        }
        return buildManifest(best);
    }

    /**
     * Extract the trailing build number of a release tag
     * ({@code 6.5.0.490} to {@code 490}, {@code v6.7.0} to {@code 0}).
     *
     * @param tag The release tag name.
     * @return The build number, or {@code -1} when the tag has no number.
     */
    private static long tagBuildNumber(String tag) {
        if (tag == null) {
            return -1L;
        }
        int end = tag.length();
        while (end > 0 && !Character.isDigit(tag.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(tag.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return -1L;
        }
        try {
            return Long.parseLong(tag.substring(start, end));
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private Manifest buildManifest(JSONObject release) {
        JSONObject apk = findApkAsset(release);
        String downloadUrl = apk == null ? "" : apk.optString("browser_download_url", "");
        String tag = release.optString("tag_name", "");
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String changelog = release.optString("body", "").trim();
        if (changelog.isEmpty()) {
            changelog = release.optString("name", "").trim();
        }
        // An explicit manifest.json asset wins: it carries the real version code.
        JSONObject manifestAsset = findAsset(release, MANIFEST_ASSET_NAME);
        if (manifestAsset != null) {
            JSONObject json = fetchJson(manifestAsset.optString("browser_download_url", ""));
            if (json != null) {
                int versionCode = json.optInt("versionCode", -1);
                String manifestVersion = json.optString("version", version);
                String manifestChangelog = json.optString("changelog", changelog);
                return new Manifest(manifestVersion, versionCode, manifestChangelog, downloadUrl,
                        versionCode > this.versionInfo.code);
            }
        }
        boolean updateAvailable = compareVersions(version, this.versionInfo.name) > 0;
        return new Manifest(version, -1, changelog, downloadUrl, updateAvailable);
    }

    private JSONObject fetchJson(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "ADBlock/" + this.versionInfo.name)
                .build();
        try (Response response = this.client.newCall(request).execute();
             ResponseBody body = response.body()) {
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            return new JSONObject(body.string());
        } catch (IOException | JSONException exception) {
            Timber.w(exception, "Unable to read the release manifest asset.");
            return null;
        }
    }

    private static JSONObject findAsset(JSONObject release, String name) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && name.equalsIgnoreCase(asset.optString("name", ""))) {
                return asset;
            }
        }
        return null;
    }

    /**
     * Find the APK asset of a release, tolerating any file name the release uses.
     */
    private static JSONObject findApkAsset(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        JSONObject fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (!name.toLowerCase().endsWith(".apk")) {
                continue;
            }
            String lower = name.toLowerCase();
            if (lower.startsWith("adblock-latest") || lower.startsWith("adblock-")) {
                return asset;
            }
            if (fallback == null) {
                fallback = asset;
            }
        }
        return fallback;
    }

    /**
     * Compare two dotted version names numerically.
     *
     * @return A negative value when {@code left} is older than {@code right}.
     */
    static int compareVersions(String left, String right) {
        List<Integer> a = versionParts(left);
        List<Integer> b = versionParts(right);
        int size = Math.max(a.size(), b.size());
        for (int i = 0; i < size; i++) {
            int va = i < a.size() ? a.get(i) : 0;
            int vb = i < b.size() ? b.get(i) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Integer> versionParts(String version) {
        List<Integer> parts = new ArrayList<>();
        if (version == null) {
            return parts;
        }
        for (String token : version.split("[^0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                parts.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // Ignore oversized components.
            }
        }
        return parts;
    }

    /**
     * Update the application to the latest version.
     *
     * @return The download identifier ({@code -1} if download was not started).
     */
    public long update() {
        // Check manifest
        Manifest manifest = this.manifest.getValue();
        if (manifest == null) {
            manifest = this.cachedManifest;
        }
        if (manifest == null) {
            return -1;
        }
        // Drop the previous one-shot receiver. It unregisters itself as soon as
        // its own download finished, so unregistering it again is expected to
        // fail with IllegalArgumentException.
        if (this.receiver != null) {
            try {
                this.context.unregisterReceiver(this.receiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered by the receiver itself
            }
            this.receiver = null;
        }
        // Queue download
        long downloadId = download(manifest);
        // Register new broadcast receiver
        this.receiver = new ApkDownloadReceiver(downloadId);
        // RECEIVER_NOT_EXPORTED: ACTION_DOWNLOAD_COMPLETE is sent by the system
        // DownloadManager, so no other app needs (or gets) access to this
        // receiver. EXPORTED also let any app wake the process at will.
        ContextCompat.registerReceiver(this.context, this.receiver,
                new IntentFilter(ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED);
        // Return download identifier
        return downloadId;
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
