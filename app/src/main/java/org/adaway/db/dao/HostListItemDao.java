package org.adaway.db.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import org.adaway.db.entity.HostListItem;

import java.util.List;
import java.util.Optional;

import static androidx.room.OnConflictStrategy.REPLACE;

/**
 * This interface is the DAO for {@link HostListItem} entities.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@Dao
public interface HostListItemDao {
    @Insert(onConflict = REPLACE)
    void insert(HostListItem... item);

    @Insert(onConflict = REPLACE)
    void insert(List<HostListItem> items);

    @Update
    void update(HostListItem item);

    @Delete
    void delete(HostListItem item);

    @Query("DELETE FROM hosts_lists WHERE source_id = 1 AND host = :host")
    void deleteUserFromHost(String host);

    @Query("SELECT * FROM hosts_lists WHERE type = :type AND host LIKE :query AND ((:includeSources == 0 AND source_id == 1) || (:includeSources == 1)) GROUP BY host ORDER BY host ASC")
    PagingSource<Integer, HostListItem> loadList(int type, boolean includeSources, String query);

    @Query("SELECT * FROM hosts_lists ORDER BY host ASC")
    List<HostListItem> getAll();

    @Query("SELECT * FROM hosts_lists WHERE type = :type ORDER BY host ASC LIMIT :limit")
    List<HostListItem> getListByType(int type, int limit);

    @Query("SELECT COUNT(*) FROM hosts_lists WHERE type = :type")
    int getCountByType(int type);

    @Query("SELECT * FROM hosts_lists WHERE source_id = 1")
    List<HostListItem> getUserList();

    @Query("SELECT id FROM hosts_lists WHERE host = :host AND source_id = 1 LIMIT 1")
    Optional<Integer> getHostId(String host);

    /*
     * BUG FIX (stats accuracy): these counters used to query hosts_lists
     * (the raw, per-source merged data). That over-reports what is really
     * in effect: hosts excluded by the allow-list were still counted as
     * "blocked", and a host blocked by one source but redirected by
     * another was counted in both categories. The counters now read from
     * host_entries — the exact table the generated hosts file is written
     * from after the dedup/allow/redirect sync — so the home screen
     * numbers match the file that is actually applied.
     */
    @Query("SELECT COUNT(*) FROM host_entries WHERE type = 0")
    LiveData<Integer> getBlockedHostCount();

    @Query("SELECT COUNT(DISTINCT host) FROM hosts_lists WHERE type = 1 AND enabled = 1")
    LiveData<Integer> getAllowedHostCount();

    @Query("SELECT COUNT(*) FROM host_entries WHERE type = 2")
    LiveData<Integer> getRedirectHostCount();

    @Query("DELETE FROM hosts_lists WHERE source_id = :sourceId")
    void clearSourceHosts(int sourceId);
}
