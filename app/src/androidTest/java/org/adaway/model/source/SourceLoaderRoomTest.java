package org.adaway.model.source;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.adaway.db.AppDatabase;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.ListType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/** A private in-memory database only: never touches production app data, VPN or system settings. */
@RunWith(AndroidJUnit4.class)
public class SourceLoaderRoomTest {
    private AppDatabase database;
    private HostsSource source;
    @Before public void createDatabase() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).allowMainThreadQueries().build();
        HostsSource user = new HostsSource(); user.setId(1); user.setLabel("test user"); user.setUrl(HostsSource.USER_SOURCE_URL);
        database.hostsSourceDao().insert(user);
        source = new HostsSource(); source.setId(2); source.setLabel("test source");
        source.setUrl("https://example.test/hosts"); source.setRedirectEnabled(true);
        database.hostsSourceDao().insert(source);
    }
    @After public void closeDatabase() { database.close(); }
    private void load(String text) throws IOException {
        new SourceLoader(source).parse(new BufferedReader(new StringReader(text)), database.hostsListItemDao(), database);
    }
    @Test public void networkFailureAfterAnInsertedBatchRollsBackAndKeepsOldSource() throws Exception {
        load("0.0.0.0 old.test\n");
        String text = "0.0.0.0 new.test\n".repeat(101);
        StringReader failing = new StringReader(text) {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count < 0) throw new IOException("simulated download interruption");
                return count;
            }
        };
        try {
            new SourceLoader(source).parse(new BufferedReader(failing), database.hostsListItemDao(), database); fail();
        } catch (IOException expected) { }
        assertEquals(1, database.hostsListItemDao().getAll().size());
        assertEquals("old.test", database.hostsListItemDao().getAll().get(0).getHost());
    }
    @Test public void malformedInputRollsBackButACompleteEmptyFileClearsIntentionally() throws Exception {
        load("0.0.0.0 old.test\n");
        try { load("0.0.0.0 new.test\n999.1.1.1 bad.test\n"); fail(); }
        catch (IOException expected) { }
        assertEquals("old.test", database.hostsListItemDao().getAll().get(0).getHost());
        load("# intentionally empty\n"); assertTrue(database.hostsListItemDao().getAll().isEmpty());
    }
    @Test public void effectiveDaoPriorityMatchesUserExceptionReplacementBlockOrder() throws Exception {
        load("0.0.0.0 ads.test\n192.0.2.8 ads.test\n@@||ads.test^\n");
        database.hostEntryDao().sync(); assertNull(database.hostEntryDao().getEntry("ads.test"));
        HostListItem user = new HostListItem(); user.setHost("ads.test"); user.setSourceId(1);
        user.setType(ListType.BLOCKED); user.setEnabled(true); database.hostsListItemDao().insert(user);
        database.hostEntryDao().sync(); assertEquals(ListType.BLOCKED, database.hostEntryDao().getEntry("ads.test").getType());
        user.setType(ListType.ALLOWED); database.hostsListItemDao().insert(user);
        database.hostEntryDao().sync(); assertNull(database.hostEntryDao().getEntry("ads.test"));
    }
    @Test public void underscoreIsLiteralAndExistingAllowWildcardsStillWork() throws Exception {
        load("0.0.0.0 a_b.test axb.test sub.wild.test\n");
        HostListItem allow = new HostListItem(); allow.setHost("a_b.test"); allow.setSourceId(1);
        allow.setType(ListType.ALLOWED); allow.setEnabled(true); database.hostsListItemDao().insert(allow);
        database.hostEntryDao().sync(); assertNull(database.hostEntryDao().getEntry("a_b.test"));
        assertNotNull(database.hostEntryDao().getEntry("axb.test"));
        allow.setHost("*.wild.test"); database.hostsListItemDao().insert(allow);
        database.hostEntryDao().sync(); assertNull(database.hostEntryDao().getEntry("sub.wild.test"));
    }
}
