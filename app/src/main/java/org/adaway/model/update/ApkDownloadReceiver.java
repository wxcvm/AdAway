package org.adaway.model.update;

import static android.content.Intent.ACTION_INSTALL_PACKAGE;
import static android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.adaway.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

import timber.log.Timber;

/**
 * This class is a {@link BroadcastReceiver} to install downloaded application updates.
 *
 * <p>Before handing anything to the package installer the downloaded archive is
 * verified (package name, version code and signing certificate) so an update
 * fetched from the public release feed can never downgrade or replace the
 * application with a foreign build.</p>
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class ApkDownloadReceiver extends BroadcastReceiver {
    private static final java.util.concurrent.atomic.AtomicBoolean VERIFYING = new java.util.concurrent.atomic.AtomicBoolean();
    private final long downloadId;

    /** Manifest receiver survives a download outliving the app process. */
    public ApkDownloadReceiver() { this.downloadId = -1; }

    public ApkDownloadReceiver(long downloadId) {
        this.downloadId = downloadId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()) || id < 0
                || (this.downloadId >= 0 && this.downloadId != id)
                || !UpdateModel.pendingDownloadAllowed(context, id)) return;
        // WorkManager keeps verification alive after this receiver returns. The
        // worker independently checks DownloadManager state, package and signer.
        ApkInstallWorker.enqueue(context, id);
    }

    /** User-triggered fallback, not a silent or privileged install. */
    public static void resumeInstall(Context context, long id) {
        if (id < 0) return;
        Context app = context.getApplicationContext();
        new Thread(() -> new ApkDownloadReceiver(id).verifyAndInstall(app, id, true),
                "apk-update-foreground-retry").start();
    }

    void verifyAndInstall(Context context, long id, boolean interactive) {
        if (!VERIFYING.compareAndSet(false, true)) return;
        try { verifyDownload(context, id, interactive); }
        finally { VERIFYING.set(false); }
    }

    private void verifyDownload(Context context, long id, boolean interactive) {
        if (!UpdateModel.pendingDownloadAllowed(context, id)) return;
        DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
        if (downloadManager == null) {
            Timber.w("DownloadManager missing while verifying APK update");
            return;
        }
        try (android.database.Cursor cursor = downloadManager.query(
                new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                Timber.w("APK download %s not found", id);
                return;
            }
            int index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (index < 0 || cursor.getInt(index) != DownloadManager.STATUS_SUCCESSFUL) {
                Timber.w("APK download %s is not complete", id);
                return;
            }
        } catch (Exception exception) {
            Timber.w(exception, "Unable to inspect downloaded update");
            return;
        }
        Uri apkUri = downloadManager.getUriForDownloadedFile(id);
        if (apkUri == null) {
            Timber.w("Failed to download id: %s.", id);
            return;
        }
        final File copy;
        try { copy = File.createTempFile("downloaded-update-" + id + "-", ".apk", context.getCacheDir()); }
        catch (java.io.IOException exception) { Timber.w(exception, "Cannot stage downloaded APK"); return; }
        int problem = verify(context, apkUri, copy);
        Handler main = new Handler(Looper.getMainLooper());
        if (problem == 0) {
            main.post(() -> {
                if (!UpdateModel.pendingDownloadAllowed(context, id)) {
                    discard(copy); return;
                }
                boolean foreground = androidx.lifecycle.ProcessLifecycleOwner.get().getLifecycle()
                        .getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
                if (interactive || foreground) {
                    UpdateModel.clearVerifiedCopiesExcept(context, id, copy);
                    Uri verified = androidx.core.content.FileProvider.getUriForFile(context,
                            context.getPackageName() + ".fileprovider", copy);
                    installApk(context, verified);
                } else {
                    // The foreground continuation verifies again. Do not retain
                    // a multi-megabyte cache copy only to show a notification.
                    discard(copy);
                    org.adaway.helper.NotificationHelper.showUpdateApplicationNotification(context);
                }
            });
        } else {
            String reason = context.getString(problem);
            Timber.w("Refusing downloaded update: %s.", reason);
            main.post(() -> Toast.makeText(context,
                    context.getString(R.string.update_verify_failed, reason),
                    Toast.LENGTH_LONG).show());
        }
    }

    private static void discard(File copy) {
        if (copy.exists() && !copy.delete()) Timber.w("Cannot discard staged APK update");
    }

    private void installApk(Context context, Uri apkUri) {
        Intent install = new Intent(ACTION_INSTALL_PACKAGE);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
        try {
            context.startActivity(install);
        } catch (android.content.ActivityNotFoundException | SecurityException exception) {
            Timber.w(exception, "No permitted installer can open the downloaded APK");
            Toast.makeText(context,
                    context.getString(R.string.update_verify_unreadable), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Verify a downloaded update archive.
     *
     * @return {@code 0} when the archive can be installed, otherwise the string
     * resource id explaining why it was rejected.
     */
    private int verify(Context context, Uri apkUri, File copy) {
        boolean keep = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(apkUri);
                 FileOutputStream output = new FileOutputStream(copy)) {
                if (input == null) {
                    return R.string.update_verify_unreadable;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                long copied = 0;
                while ((read = input.read(buffer)) > 0) {
                    copied += read;
                    if (copied > 64L * 1024 * 1024) return R.string.update_verify_unreadable;
                    output.write(buffer, 0, read);
                }
            } catch (Exception exception) {
                Timber.w(exception, "Failed to read the downloaded APK.");
                return R.string.update_verify_unreadable;
            }
            PackageManager packageManager = context.getPackageManager();
            PackageInfo archive = packageManager.getPackageArchiveInfo(copy.getAbsolutePath(), GET_SIGNING_CERTIFICATES);
            if (archive == null) {
                return R.string.update_verify_unparsable;
            }
            if (!context.getPackageName().equals(archive.packageName)) {
                return R.string.update_verify_package;
            }
            PackageInfo current = packageManager.getPackageInfo(context.getPackageName(), GET_SIGNING_CERTIFICATES);
            // Installing an equal-version APK is not an update: this used to
            // silently reinstall 758 when a cached fixed-tag asset was stale
            // while the update manifest already advertised 773.
            if (archive.getLongVersionCode() <= current.getLongVersionCode()) {
                Timber.w("Downloaded APK is not newer: current=%s archive=%s",
                        current.getLongVersionCode(), archive.getLongVersionCode());
                return R.string.update_verify_version;
            }
            long expected = UpdateModel.expectedDownloadedVersionCode(context);
            if (expected > 0 && archive.getLongVersionCode() != expected) {
                Timber.w("Downloaded APK version does not match feed: expected=%s archive=%s",
                        expected, archive.getLongVersionCode());
                return R.string.update_verify_version;
            }
            String archiveSigner = signerDigest(archive);
            if (archiveSigner == null || !archiveSigner.equals(signerDigest(current))) {
                return R.string.update_verify_signature;
            }
            keep = true;
            return 0;
        } catch (Exception exception) {
            Timber.w(exception, "Failed to verify the downloaded APK.");
            return R.string.update_verify_unparsable;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            /*
             * 不能忽略 delete() 的返回值：清理失败时要留下痕迹，否则缓存里会一直留着
             * 上次下载的 APK（占用空间，也可能让下一次校验看到过期文件）。
             */
            if (!keep && copy.exists() && !copy.delete()) {
                android.util.Log.w("ApkDownloadReceiver", "Failed to delete cached update APK: " + copy);
            }
        }
    }

    private static String signerDigest(PackageInfo packageInfo) {
        try {
            Signature[] signatures = packageInfo.signingInfo.getApkContentsSigners();
            if (signatures.length != 1) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest(signatures[0].toByteArray())) {
                builder.append(String.format(Locale.US, "%02X", b));
            }
            return builder.toString();
        } catch (Exception exception) {
            Timber.w(exception, "Failed to compute the signer digest.");
            return null;
        }
    }
}
