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
    @Test public void localUiEditsPublishWithoutDownloadAndInvalidEditsRollBack() throws Exception {
        load("||ads.test^\n");
        SourceModel model = new SourceModel(ApplicationProvider.getApplicationContext(), database);
        model.syncHostEntries();
        HostListItem allow = new HostListItem();
        allow.setHost("child.ads.test"); allow.setSourceId(1);
        allow.setEnabled(true); allow.setType(ListType.ALLOWED);
        model.editRules(() -> database.hostsListItemDao().insert(allow));
        assertEquals(org.adaway.model.traffic.TrafficCoreContract.Action.PASS,
                model.getRuleSnapshot().decide("child.ads.test", 1, 1).action);
        model.editRules(() -> database.hostsListItemDao().deleteUserFromHost("child.ads.test"));
        assertEquals(org.adaway.model.traffic.TrafficCoreContract.Action.NXDOMAIN,
                model.getRuleSnapshot().decide("child.ads.test", 1, 1).action);
        long generation = model.getRuleSnapshot().generation;
        HostListItem invalid = new HostListItem();
        invalid.setHost("bad.test"); invalid.setSourceId(1);
        invalid.setType(ListType.REDIRECTED); invalid.setEnabled(true); invalid.setRedirection("not-an-ip");
        try {
            model.editRules(() -> database.hostsListItemDao().insert(invalid));
            fail("Invalid local edit must not commit");
        } catch (IllegalStateException expected) { /* transaction rolled back */ }
        assertEquals(generation, model.getRuleSnapshot().generation);
        assertTrue(database.hostsListItemDao().getAll().stream().noneMatch(rule -> rule.getHost().equals("bad.test")));
        model.editRules(() -> {
            database.hostsSourceDao().setSourceEnabled(2, false);
            database.hostsSourceDao().setSourceItemsEnabled(2, false);
        });
        assertEquals(org.adaway.model.traffic.TrafficCoreContract.Action.PASS,
                model.getRuleSnapshot().decide("child.ads.test", 1, 1).action);
    }
    @Test public void atomicRuleEditingWorksOnTheSupportedAndroidRuntime() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        java.io.File file = java.io.File.createTempFile("adaway-rule-test-", ".txt", context.getCacheDir());
        try {
            AtomicRuleFile.update(file, previous -> "one.test\n");
            AtomicRuleFile.update(file, previous -> previous + "two.test\n");
            assertEquals("one.test\ntwo.test\n", new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } finally { java.nio.file.Files.deleteIfExists(file.toPath()); }
    }
    private void load(String text) throws IOException {
        new SourceLoader(source).parse(new BufferedReader(new StringReader(text)), database.hostsListItemDao(), database);
    }
    @Test public void persistedSuffixExceptionAndReplacementDriveRealDnsAndHostsExport() throws Exception {
        load("||ads.test^\n0.0.0.0 child.ads.test\n@@||allowed.ads.test^\n192.0.2.8 redirect.test\n");
        org.adaway.model.traffic.RuleSnapshotRepository.Snapshot snapshot =
                PersistedRuleSnapshots.prepare(database.hostsListItemDao(), 1);
        assertNotNull(snapshot);
        assertEquals(1, database.hostsListItemDao().getAll().stream()
                .filter(r -> r.getHost().equals("ads.test")).findFirst().get().getMatchScope());
        assertEquals(2, snapshot.resolve("sub.ads.test", 1, 1).rule.sourceId);
        assertEquals(org.adaway.model.traffic.TrafficCoreContract.Action.PASS,
                snapshot.decide("child.allowed.ads.test", 1, 1).action);
        byte[] blocked = snapshot.answerDns(dns("sub.ads.test", 1));
        assertNotNull(blocked); assertEquals(3, blocked[3]);
        byte[] replaced = snapshot.answerDns(dns("redirect.test", 1));
        assertNotNull(replaced); assertEquals(8, replaced[replaced.length - 1]);
        assertNull(snapshot.answerDns(dns("redirect.test", 28)));
        assertNull(snapshot.answerDns(dns("child.allowed.ads.test", 1)));
        database.hostEntryDao().sync();
        assertNull(database.hostEntryDao().getEntry("sub.ads.test")); // No invented subdomains.
        assertNotNull(database.hostEntryDao().getEntry("child.ads.test"));
        database.hostsSourceDao().setSourceEnabled(2, false);
        snapshot = PersistedRuleSnapshots.prepare(database.hostsListItemDao(), 2);
        assertNotNull(snapshot); assertTrue(snapshot.records.isEmpty());
        database.hostsSourceDao().setSourceEnabled(2, true);
        database.hostsSourceDao().delete(source);
        assertTrue(PersistedRuleSnapshots.prepare(database.hostsListItemDao(), 3).records.isEmpty());
    }
    static byte[] dns(String host, int type) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[12]; header[2] = 1; header[5] = 1; out.write(header, 0, 12);
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(bytes.length); out.write(bytes, 0, bytes.length);
        }
        out.write(0); out.write(type >>> 8); out.write(type & 255); out.write(0); out.write(1);
        return out.toByteArray();
    }
    @Test public void versionEightMigrationPreservesLegacyScopeThenRefreshAndRestartRebuildTypedRules() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String name = "rule-migration-" + java.util.UUID.randomUUID() + ".db";
        java.io.File path = context.getDatabasePath(name); path.getParentFile().mkdirs();
        AppDatabase migrated = null;
        try {
            // Reconstruct the tracked v7 schema plus the exact v8 composite index.
            // No guessed Room identity hash or destructive migration is used.
            String schema;
            try (java.io.InputStream input = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getContext().getAssets().open("org.adaway.db.AppDatabase/7.json")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                schema = bytes.toString("UTF-8");
            }
            org.json.JSONArray entities = new org.json.JSONObject(schema).getJSONObject("database").getJSONArray("entities");
            try (android.database.sqlite.SQLiteDatabase old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)) {
                for (int i = 0; i < entities.length(); i++) {
                    org.json.JSONObject entity = entities.getJSONObject(i); String table = entity.getString("tableName");
                    old.execSQL(entity.getString("createSql").replace("${TABLE_NAME}", table));
                    org.json.JSONArray indexes = entity.getJSONArray("indices");
                    for (int j = 0; j < indexes.length(); j++) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}", table));
                }
                old.execSQL("CREATE INDEX index_hosts_lists_type_enabled_host ON hosts_lists(type, enabled, host)");
                old.execSQL("INSERT INTO hosts_sources(id,label,url,enabled,allowEnabled,redirectEnabled,size) VALUES(2,'test','https://fixture.test',1,0,1,1)");
                old.execSQL("INSERT INTO hosts_lists(id,host,type,enabled,source_id) VALUES(1,'legacy.test',0,1,2)");
                old.setVersion(8);
            }
            migrated = Room.databaseBuilder(context, AppDatabase.class, name).allowMainThreadQueries()
                    .addMigrations(org.adaway.db.Migrations.MIGRATION_8_9).build();
            HostListItem legacy = migrated.hostsListItemDao().getAll().get(0);
            assertEquals(-1, legacy.getMatchScope());
            org.adaway.model.traffic.RuleSnapshotRepository.Snapshot snapshot = PersistedRuleSnapshots.prepare(migrated.hostsListItemDao(), 1);
            assertTrue(snapshot.records.get(0).legacy);
            assertEquals(org.adaway.model.traffic.TrafficCoreContract.Action.PASS, snapshot.decide("sub.legacy.test", 1, 1).action);
            HostsSource refresh = new HostsSource(); refresh.setId(2); refresh.setRedirectEnabled(true);
            new SourceLoader(refresh).parse(new BufferedReader(new StringReader("||legacy.test^\n")), migrated.hostsListItemDao(), migrated);
            migrated.close(); migrated = Room.databaseBuilder(context, AppDatabase.class, name).allowMainThreadQueries().build();
            snapshot = PersistedRuleSnapshots.prepare(migrated.hostsListItemDao(), 1);
            assertFalse(snapshot.records.get(0).legacy);
            assertEquals(org.adaway.model.traffic.TrafficCoreContract.Action.NXDOMAIN, snapshot.decide("sub.legacy.test", 1, 1).action);
        } finally { if (migrated != null) migrated.close(); context.deleteDatabase(name); }
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
    @Test public void invalidPersistedMetadataCannotEraseTheLastEffectiveHostsTable() throws Exception {
        load("0.0.0.0 old.test\n"); database.hostEntryDao().sync();
        HostListItem row = database.hostsListItemDao().getAll().get(0);
        row.setMatchScope(99); database.hostsListItemDao().update(row);
        try { database.hostEntryDao().sync(); fail(); } catch (IllegalStateException expected) { }
        assertNotNull(database.hostEntryDao().getEntry("old.test"));
    }
    @Test public void sharedVectorsPassThroughActualRoomImportPersistenceAndDns() throws Exception {
        java.util.Map<String, java.util.List<String[]>> cases = new java.util.LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext()
                        .getAssets().open("typed_rule_vectors.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                cases.computeIfAbsent(fields[1], unused -> new java.util.ArrayList<>()).add(fields);
            }
        }
        int queries = 0;
        for (java.util.List<String[]> fixture : cases.values()) {
            Context context = ApplicationProvider.getApplicationContext();
            AppDatabase db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).allowMainThreadQueries().build();
            try {
                for (int id = 1; id <= 4; id++) {
                    HostsSource input = new HostsSource(); input.setId(id); input.setLabel("fixture");
                    input.setUrl(id == 1 ? HostsSource.USER_SOURCE_URL : "https://fixture.test/" + id);
                    db.hostsSourceDao().insert(input);
                    StringBuilder text = new StringBuilder(); long order = 0;
                    for (String[] f : fixture) {
                        if (!f[0].equals("R") || Integer.parseInt(f[2]) != id) continue;
                        input.setAllowEnabled(f[3].equals("1")); input.setRedirectEnabled(f[4].equals("1"));
                        if (id != 1) text.append(f[5]).append('\n');
                        else for (DomainSourceParser.Entry entry : DomainSourceParser.parse(f[5], f[3].equals("1"), f[4].equals("1")).entries) {
                            // User rules use the same typed DAO insertion as the rules editor,
                            // rather than pretending the user source is a downloaded subscription.
                            HostListItem row = new HostListItem(); row.setHost(entry.domain); row.setSourceId(1); row.setEnabled(true);
                            row.setMatchScope(entry.scope.ordinal()); row.setSourceOrder(++order); row.setRedirection(entry.address);
                            row.setType(entry.kind == DomainSourceParser.Kind.ALLOW ? ListType.ALLOWED
                                    : entry.kind == DomainSourceParser.Kind.REPLACE ? ListType.REDIRECTED : ListType.BLOCKED);
                            db.hostsListItemDao().insert(row);
                        }
                    }
                    if (id != 1) new SourceLoader(input).parse(new BufferedReader(new StringReader(text.toString())), db.hostsListItemDao(), db);
                }
                org.adaway.model.traffic.RuleSnapshotRepository.Snapshot snapshot = PersistedRuleSnapshots.prepare(db.hostsListItemDao(), 1);
                assertNotNull(snapshot); db.hostEntryDao().syncPrepared(snapshot);
                for (String[] f : fixture) {
                    if (!f[0].equals("Q")) continue;
                    String label = String.join("\t", f);
                    org.adaway.model.traffic.RuleSnapshotRepository.Resolution result = snapshot.resolve(f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                    assertEquals(label, Integer.parseInt(f[5]), result.decision.action.ordinal());
                    assertEquals(label, Long.parseLong(f[6]), result.rule == null ? 0 : result.rule.sourceId);
                    // User ordering uses DB row identity; source ordering is the emitted ordinal.
                    if (result.rule == null || !result.rule.user()) assertEquals(label, Long.parseLong(f[7]), result.rule == null ? 0 : result.rule.order);
                    assertEquals(label, Integer.parseInt(f[8]), result.rule == null ? 0 : result.rule.scope.ordinal());
                    assertEquals(label, f[9].equals("-") ? null : f[9], result.address);
                    byte[] query = dns(f[2].endsWith(".") ? f[2].substring(0, f[2].length() - 1) : f[2], Integer.parseInt(f[3]));
                    query[query.length - 1] = (byte)Integer.parseInt(f[4]);
                    byte[] answer = snapshot.answerDns(query);
                    if (result.decision.action == org.adaway.model.traffic.TrafficCoreContract.Action.PASS) assertNull(label, answer);
                    else { assertNotNull(label, answer); assertEquals(label, result.decision.action == org.adaway.model.traffic.TrafficCoreContract.Action.NXDOMAIN ? 3 : 0, answer[3]); }
                    queries++;
                }
            } finally { db.close(); }
        }
        assertEquals(30, queries);
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
