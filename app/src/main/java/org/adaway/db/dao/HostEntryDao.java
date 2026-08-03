package org.adaway.db.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import org.adaway.db.entity.HostEntry;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.ListType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static androidx.room.OnConflictStrategy.REPLACE;
import static org.adaway.db.entity.ListType.REDIRECTED;

/**
 * This interface is the DAO for {@link HostEntry} records.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@Dao
public interface HostEntryDao {
    Pattern ANY_CHAR_PATTERN = Pattern.compile("\\*");
    Pattern A_CHAR_PATTERN = Pattern.compile("\\?");

    @Query("DELETE FROM `host_entries`")
    void clear();

    @Query("INSERT INTO `host_entries` SELECT DISTINCT `host`, `type`, `redirection` FROM `hosts_lists` WHERE `type` = 0 AND `enabled` = 1")
    void importBlocked();

    @Query("SELECT host FROM hosts_lists WHERE type = 1 AND enabled = 1")
    List<String> getEnabledAllowedHosts();

    @Query("DELETE FROM `host_entries` WHERE `host` LIKE :hostPattern")
    void allowHost(String hostPattern);

    @Query("SELECT * FROM hosts_lists WHERE type = 2 AND enabled = 1 ORDER BY host ASC, source_id DESC")
    List<HostListItem> getEnabledRedirectedHosts();

    /**
     * Batch-insert redirected host entries, used by {@link #sync()}.
     * <p>
     * OPTIMIZATION: sync() used to insert redirected hosts one row at a
     * time - one round trip / one implicit commit per row. Room generates
     * a single prepared-statement loop for a List insert, so passing the
     * whole batch here instead cuts that down to one call (and, combined
     * with {@link Transaction @Transaction} on sync(), one commit for the
     * whole sync instead of one per row).
     */
    @Insert(onConflict = REPLACE)
    void redirectHosts(List<HostEntry> redirections);

    /**
     * Synchronize the host entries based on the current hosts lists table records.
     * <p>
     * OPTIMIZATION / CORRECTNESS: previously not annotated with
     * {@link Transaction @Transaction}, so clear(), importBlocked(), every
     * per-host allowHost() delete, and every per-host redirectHost() insert
     * each committed as its own separate transaction - for a device with a
     * sizeable allow-list or redirect-list this was many individual disk
     * commits for a single logical sync. It also meant a process death or
     * crash partway through left host_entries (the table the ad-blocking
     * service actually reads from) in an inconsistent half-synced state -
     * e.g. blocked hosts imported but allow-list exclusions not yet
     * applied. Wrapping the whole method in one transaction makes the sync
     * atomic (either fully applied or not applied at all) and lets SQLite
     * commit it once instead of N+M times.
     */
    @Transaction
    default void sync() {
        clear();
        importBlocked();
        for (String allowedHost : getEnabledAllowedHosts()) {
            allowedHost = ANY_CHAR_PATTERN.matcher(allowedHost).replaceAll("%");
            allowedHost = A_CHAR_PATTERN.matcher(allowedHost).replaceAll("_");
            allowHost(allowedHost);
        }
        List<HostListItem> redirectedHosts = getEnabledRedirectedHosts();
        List<HostEntry> redirections = new ArrayList<>(redirectedHosts.size());
        for (HostListItem redirectedHost : redirectedHosts) {
            HostEntry entry = new HostEntry();
            entry.setHost(redirectedHost.getHost());
            entry.setType(REDIRECTED);
            entry.setRedirection(redirectedHost.getRedirection());
            redirections.add(entry);
        }
        redirectHosts(redirections);
    }

    @Query("SELECT * FROM `host_entries` ORDER BY `host`")
    List<HostEntry> getAll();

    /**
     * Same rows as {@link #getAll()}, as a raw Cursor instead of a
     * materialized List<HostEntry>.
     * <p>
     * OPTIMIZATION: writing the generated hosts file iterates every row
     * exactly once, in order, and needs only 3 columns - there's no
     * reason to pay for allocating one HostEntry object per row (this
     * table commonly holds 100k-300k+ rows after merging several
     * community block lists) just to immediately discard them after a
     * single pass. Room supports returning a Cursor directly precisely
     * for this kind of large-sequential-scan case, avoiding both the
     * object allocation and holding the entire result set in memory at
     * once.
     */
    @Query("SELECT `host`, `type`, `redirection` FROM `host_entries` ORDER BY `host`")
    android.database.Cursor getAllCursor();

    @Query("SELECT `type` FROM `host_entries` WHERE `host` == :host LIMIT 1")
    ListType getTypeOfHost(String host);

    @Query("SELECT IFNULL((SELECT `type` FROM `host_entries` WHERE `host` == :host LIMIT 1), 1)")
    ListType getTypeForHost(String host);

    @Nullable
    @Query("SELECT * FROM `host_entries` WHERE `host` == :host LIMIT 1")
    HostEntry getEntry(String host);
}
