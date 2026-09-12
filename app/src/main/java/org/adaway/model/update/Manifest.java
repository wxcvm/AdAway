package org.adaway.model.update;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * This class represents an application release.
 *
 * <p>Two shapes are supported:</p>
 * <ul>
 *     <li>the historic <code>manifest.json</code> release asset
 *     (<code>{"version":…,"versionCode":…,"changelog":…}</code>), which carries an
 *     explicit, authoritative version code;</li>
 *     <li>a plain GitHub release (<code>wxcvm/Doh-ECH</code>), where the version
 *     comes from the tag name and the changelog from the release body.</li>
 * </ul>
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class Manifest {
    public final String version;
    /**
     * The version code of the release, or {@code -1} when the release does not
     * advertise one (plain GitHub release without a manifest.json asset).
     */
    public final int versionCode;
    public final String changelog;
    /**
     * The direct download URL of the APK asset (empty when unknown).
     */
    public final String downloadUrl;
    public final boolean updateAvailable;

    /**
     * Constructor from a manifest.json content.
     *
     * @param manifest          The manifest JSON content.
     * @param currentVersionCode The version code of the running application.
     * @throws JSONException If the manifest could not be parsed.
     */
    public Manifest(String manifest, long currentVersionCode) throws JSONException {
        JSONObject manifestObject = new JSONObject(manifest);
        this.version = manifestObject.getString("version");
        this.versionCode = manifestObject.getInt("versionCode");
        this.changelog = manifestObject.optString("changelog", "");
        this.downloadUrl = manifestObject.optString("downloadUrl", "");
        this.updateAvailable = this.versionCode > currentVersionCode;
    }

    /**
     * Constructor from a GitHub release.
     *
     * @param version         The release version (tag name without leading "v").
     * @param versionCode     The version code, {@code -1} when unknown.
     * @param changelog       The release notes.
     * @param downloadUrl     The APK asset download URL.
     * @param updateAvailable Whether the release is newer than the running application.
     */
    public Manifest(String version, int versionCode, String changelog, String downloadUrl, boolean updateAvailable) {
        this.version = version;
        this.versionCode = versionCode;
        this.changelog = changelog;
        this.downloadUrl = downloadUrl;
        this.updateAvailable = updateAvailable;
    }
}
