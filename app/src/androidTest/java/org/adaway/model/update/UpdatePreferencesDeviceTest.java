package org.adaway.model.update;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.impl.WorkManagerImpl;
import org.adaway.model.source.SourceUpdateService;
import org.junit.Test;
import java.util.List;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.*;

/** Inspect actual persisted WorkManager jobs; never run download or installation. */
public class UpdatePreferencesDeviceTest {
    private static WorkInfo active(WorkManager manager, String name) throws Exception {
        List<WorkInfo> infos=manager.getWorkInfosForUniqueWork(name).get(10,SECONDS);
        for(WorkInfo info:infos) if(!info.getState().isFinished()) return info;
        return null;
    }
    @Test public void changedPreferencesReconfigureAndCancelPersistedJobs() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("update-preferences-fixture",Context.MODE_PRIVATE);
        Context isolated=new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return prefs; }
        };
        WorkManager manager=WorkManager.getInstance(app);
        try {
            assertTrue(prefs.edit().clear().putBoolean("updateCheckAppDaily",true)
                .putBoolean("updateCheckHostsDaily",true).putBoolean("updateOnlyOnWifi",true).commit());
            ApkUpdateService.preferencesChanged(isolated); SourceUpdateService.preferencesChanged(isolated);
            WorkInfo apk=active(manager,"ApkUpdateWork"); assertNotNull(apk);
            WorkInfo hosts=active(manager,"HostsUpdateWork"); assertNotNull(hosts);
            androidx.work.impl.model.WorkSpec spec=WorkManagerImpl.getInstance(app).getWorkDatabase()
                .workSpecDao().getWorkSpec(hosts.getId().toString());
            assertNotNull(spec); assertEquals(NetworkType.UNMETERED,spec.constraints.getRequiredNetworkType());
            assertTrue(prefs.edit().putBoolean("updateOnlyOnWifi",false).commit());
            SourceUpdateService.preferencesChanged(isolated);
            hosts=active(manager,"HostsUpdateWork"); assertNotNull(hosts);
            spec=WorkManagerImpl.getInstance(app).getWorkDatabase().workSpecDao().getWorkSpec(hosts.getId().toString());
            assertNotNull(spec); assertEquals(NetworkType.CONNECTED,spec.constraints.getRequiredNetworkType());
            assertTrue(prefs.edit().putBoolean("updateCheckAppDaily",false).putBoolean("updateCheckHostsDaily",false).commit());
            ApkUpdateService.preferencesChanged(isolated); SourceUpdateService.preferencesChanged(isolated);
            assertNull(active(manager,"ApkUpdateWork")); assertNull(active(manager,"HostsUpdateWork"));
        } finally {
            prefs.edit().clear().commit();
            ApkUpdateService.preferencesChanged(app); SourceUpdateService.preferencesChanged(app);
        }
    }
}
