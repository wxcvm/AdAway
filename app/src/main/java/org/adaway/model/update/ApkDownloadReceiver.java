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
    private final long downloadId;

    public ApkDownloadReceiver(long downloadId) {
        this.downloadId = downloadId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        //Fetching the download id received with the broadcast
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        //Checking if the received broadcast is for our enqueued download by matching download id
        if (this.downloadId != id ||
                !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        // An exported receiver cannot trust the broadcast alone: even if a
        // third party knows the id, it cannot fake DownloadManager state.
        DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
        if (downloadManager == null) return;
        int status;
        try (android.database.Cursor cursor = downloadManager.query(
                new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int column = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (column < 0) return;
            status = cursor.getInt(column);
        } catch (Exception exception) {
            Timber.w(exception, "Cannot verify DownloadManager completion broadcast");
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL
                && status != DownloadManager.STATUS_FAILED) {
            // Do not unregister on an early/spoofed completion intent.
            return;
        }
        // One-shot receiver: unregister before the slow verification below so
        // the app keeps no registered receiver around (UpdateModel tolerates
        // the second unregister attempt).
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
        if (status == DownloadManager.STATUS_FAILED) {
            Toast.makeText(context,
                    context.getString(R.string.compose_update_download_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Context appContext = context.getApplicationContext();
        // onReceive runs on the main thread and has ~10 s before the system
        // considers the receiver unresponsive. Verifying an update means
        // copying a multi-megabyte APK through the ContentResolver and parsing
        // it, so it must not happen here - only the install dialog is posted
        // back to the main thread.
        Thread worker = new Thread(() -> verifyAndInstall(appContext, id), "apk-update-verify");
        worker.start();
    }

    /** User-triggered fallback, not a silent or privileged install. */
    public static void resumeInstall(Context context, long id) {
        if (id < 0) return;
        Context app = context.getApplicationContext();
        new Thread(() -> new ApkDownloadReceiver(id).verifyAndInstall(app, id),
                "apk-update-foreground-retry").start();
    }

    private void verifyAndInstall(Context context, long id) {
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
        int problem = verify(context, apkUri);
        Handler main = new Handler(Looper.getMainLooper());
        if (problem == 0) {
            main.post(() -> installApk(context, apkUri));
        } else {
            String reason = context.getString(problem);
            Timber.w("Refusing downloaded update: %s.", reason);
            main.post(() -> Toast.makeText(context,
                    context.getString(R.string.update_verify_failed, reason),
                    Toast.LENGTH_LONG).show());
        }
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
    private int verify(Context context, Uri apkUri) {
        File copy = new File(context.getCacheDir(), "downloaded-update.apk");
        try (InputStream input = context.getContentResolver().openInputStream(apkUri);
             FileOutputStream output = new FileOutputStream(copy)) {
            if (input == null) {
                return R.string.update_verify_unreadable;
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            Timber.w(exception, "Failed to read the downloaded APK.");
            return R.string.update_verify_unreadable;
        }
        try {
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
            if (!copy.delete()) {
                android.util.Log.w("ApkDownloadReceiver", "Failed to delete cached update APK: " + copy);
            }
        }
    }

    private static String signerDigest(PackageInfo packageInfo) {
        try {
            Signature[] signatures = packageInfo.signingInfo.getApkContentsSigners();
            if (signatures.length == 0) {
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
