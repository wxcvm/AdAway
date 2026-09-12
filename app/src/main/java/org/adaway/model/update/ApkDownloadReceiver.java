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
        if (this.downloadId == id) {
            DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
            Uri apkUri = downloadManager.getUriForDownloadedFile(id);
            if (apkUri == null) {
                Timber.w("Failed to download id: %s.", id);
            } else {
                int problem = verify(context, apkUri);
                if (problem == 0) {
                    installApk(context, apkUri);
                } else {
                    String reason = context.getString(problem);
                    Timber.w("Refusing downloaded update: %s.", reason);
                    Toast.makeText(context, context.getString(R.string.update_verify_failed, reason), Toast.LENGTH_LONG).show();
                }
            }
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
