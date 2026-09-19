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
        if (this.downloadId != id) {
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
        Context appContext = context.getApplicationContext();
        // onReceive runs on the main thread and has ~10 s before the system
        // considers the receiver unresponsive. Verifying an update means
        // copying a multi-megabyte APK through the ContentResolver and parsing
        // it, so it must not happen here - only the install dialog is posted
        // back to the main thread.
        Thread worker = new Thread(() -> verifyAndInstall(appContext, id), "apk-update-verify");
        worker.start();
    }

    private void verifyAndInstall(Context context, long id) {
        DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
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
        install.setData(apkUri);
        context.startActivity(install);
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
            if (archive.getLongVersionCode() < current.getLongVersionCode()) {
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
            copy.delete();
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
