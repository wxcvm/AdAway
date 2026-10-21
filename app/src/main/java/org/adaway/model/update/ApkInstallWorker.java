package org.adaway.model.update;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Persisted download completion; never performs a silent privileged install. */
public final class ApkInstallWorker extends Worker {
    static void enqueue(Context context, long id) {
        if (id < 0 || !UpdateModel.pendingDownloadAllowed(context, id)) return;
        androidx.work.Data input = new androidx.work.Data.Builder().putLong("download_id", id).build();
        androidx.work.OneTimeWorkRequest work = new androidx.work.OneTimeWorkRequest.Builder(ApkInstallWorker.class)
                .setInputData(input).build();
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("apk-install-" + id,
                androidx.work.ExistingWorkPolicy.KEEP, work);
    }

    public ApkInstallWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        long id = getInputData().getLong("download_id", -1);
        if (id >= 0) new ApkDownloadReceiver(id).verifyAndInstall(getApplicationContext(), id, false);
        return Result.success();
    }
}
