package org.adaway.model.source;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.adaway.db.AppDatabase;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.db.entity.RuleListRow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

/** The actual UI queries against private Room data, including legacy source/item state differences. */
@RunWith(AndroidJUnit4.class)
public class RuleBrowserRoomTest {
    private AppDatabase db;
    @Before public void open() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).allowMainThreadQueries().build();
        for (int i = 1; i <= 4; i++) {
            HostsSource source = new HostsSource(); source.setId(i); source.setLabel("Source " + i);
            source.setUrl(i == 1 ? HostsSource.USER_SOURCE_URL : "https://example.test/" + i);
            source.setEnabled(i != 3); db.hostsSourceDao().insert(source);
        }
    }
    @After public void close() { db.close(); }
    private void insert(String host, int sourceId, int scope, boolean enabled) {
        HostListItem rule = new HostListItem(); rule.setHost(host); rule.setSourceId(sourceId);
        rule.setType(ListType.BLOCKED); rule.setMatchScope(scope); rule.setEnabled(enabled);
        db.hostsListItemDao().insert(rule);
    }
    @Test public void sameNameKeepsEachSourceAndScopeAndDisabledState() {
        insert("same.test", 2, 0, true); insert("same.test", 2, 1, true);
        insert("same.test", 3, 0, true); insert("same.test", 4, 0, false);
        insert("personal.test", 1, 0, true);
        List<RuleListRow> rows = db.hostsListItemDao().browseRules(0, "SAME", 0, 300, 0);
        assertEquals(4, rows.size()); assertEquals(4, db.hostsListItemDao().countBrowserRules(0, "same", 0));
        assertEquals(0, rows.get(0).item.getMatchScope()); assertEquals(1, rows.get(1).item.getMatchScope());
        assertTrue(rows.get(0).isActive()); assertFalse(rows.get(2).isActive()); assertFalse(rows.get(3).isActive());
        assertEquals("Source 3", rows.get(2).sourceLabel);
        assertEquals(2, db.hostsListItemDao().countBrowserRules(0, "same", 1));
        assertEquals(2, db.hostsListItemDao().countBrowserRules(0, "same", 2));
        assertEquals(1, db.hostsListItemDao().countBrowserRules(0, "", 3));
    }
    @Test public void pagesHaveStableBoundariesAndLiteralSearchDoesNotExpandWildcards() {
        for (int i = 0; i < 305; i++) insert(String.format(java.util.Locale.ROOT, "h%03d.test", i), 2, 0, true);
        List<RuleListRow> first = db.hostsListItemDao().browseRules(0, "", 0, 300, 0);
        List<RuleListRow> second = db.hostsListItemDao().browseRules(0, "", 0, 300, 300);
        assertEquals(300, first.size()); assertEquals(5, second.size());
        assertEquals("h299.test", first.get(299).item.getHost()); assertEquals("h300.test", second.get(0).item.getHost());
        assertEquals(0, db.hostsListItemDao().countBrowserRules(0, "%", 0));
        assertEquals(0, db.hostsListItemDao().countBrowserRules(0, "_", 0));
        assertEquals(1, db.hostsListItemDao().countBrowserRules(0, "H304", 0));
    }
    @Test public void disablingAndDeletingSourcesImmediatelyUpdatesBrowserCounts() {
        insert("source.test", 2, 0, true); insert("source.test", 3, 0, true);
        assertEquals(1, db.hostsListItemDao().countBrowserRules(0, "", 1));
        db.hostsSourceDao().setSourceEnabled(2, false);
        assertEquals(0, db.hostsListItemDao().countBrowserRules(0, "", 1));
        assertEquals(2, db.hostsListItemDao().countBrowserRules(0, "", 2));
        db.hostsSourceDao().delete(db.hostsSourceDao().getById(2).get());
        assertEquals(1, db.hostsListItemDao().countBrowserRules(0, "", 0));
    }
}
