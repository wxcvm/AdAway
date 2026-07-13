package org.adaway.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.adaway.R;
import org.adaway.db.converter.ListTypeConverter;
import org.adaway.db.converter.ZonedDateTimeConverter;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.HostEntry;
import org.adaway.util.AppExecutors;

import static org.adaway.db.Migrations.MIGRATION_1_2;
import static org.adaway.db.Migrations.MIGRATION_2_3;
import static org.adaway.db.Migrations.MIGRATION_3_4;
import static org.adaway.db.Migrations.MIGRATION_4_5;
import static org.adaway.db.Migrations.MIGRATION_5_6;
import static org.adaway.db.Migrations.MIGRATION_6_7;
import static org.adaway.db.entity.HostsSource.USER_SOURCE_ID;
import static org.adaway.db.entity.HostsSource.USER_SOURCE_URL;

/**
 * This class is the application database based on Room.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@Database(entities = {HostsSource.class, HostListItem.class, HostEntry.class}, version = 7)
@TypeConverters({ListTypeConverter.class, ZonedDateTimeConverter.class})
public abstract class AppDatabase extends RoomDatabase {
    /**
     * The database singleton instance.
     */
    private static volatile AppDatabase instance;

    /**
     * Get the database instance.
     *
     * @param context The application context.
     * @return The database instance.
     */
    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "app.db"
                    ).addCallback(new Callback() {
                        @Override
                        public void onCreate(@NonNull SupportSQLiteDatabase db) {
                            AppExecutors.getInstance().diskIO().execute(
                                    () -> AppDatabase.initialize(context, instance)
                            );
                        }
                    }).addMigrations(
                            MIGRATION_1_2,
                            MIGRATION_2_3,
                            MIGRATION_3_4,
                            MIGRATION_4_5,
                            MIGRATION_5_6,
                            MIGRATION_6_7
                    ).build();
                }
            }
        }
        return instance;
    }

    /**
     * Initialize the database content.
     */
    private static void initialize(Context context, AppDatabase database) {
        // Check if there is no hosts source
        HostsSourceDao hostsSourceDao = database.hostsSourceDao();
        if (!hostsSourceDao.getAll().isEmpty()) {
            return;
        }
        // User list
        HostsSource userSource = new HostsSource();
        userSource.setLabel(context.getString(R.string.hosts_user_source));
        userSource.setId(USER_SOURCE_ID);
        userSource.setUrl(USER_SOURCE_URL);
        userSource.setAllowEnabled(true);
        userSource.setRedirectEnabled(true);
        hostsSourceDao.insert(userSource);
        // BUG FIX / UPDATE: replaced the original 3 default sources
        // (AdAway official, StevenBlack, Pete Lowe) with these 7 per
        // explicit request - these are the sources actually in active
        // use, not translated/localized descriptions, so labels are
        // hardcoded short names matching how they're already labeled
        // rather than going through string resources like the old ones
        // did (those were more like descriptive taglines than short
        // identifiers, which doesn't fit this list's naming).
        HostsSource source1 = new HostsSource();
        source1.setLabel("10007_auto");
        source1.setUrl("https://raw.githubusercontent.com/lingeringsound/10007_auto/master/all");
        hostsSourceDao.insert(source1);

        HostsSource source2 = new HostsSource();
        source2.setLabel("ADblock");
        source2.setUrl("https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockhostslite.txt");
        hostsSourceDao.insert(source2);

        HostsSource source3 = new HostsSource();
        source3.setLabel("Ad-set-hosts");
        source3.setUrl("https://raw.githubusercontent.com/rentianyu/Ad-set-hosts/master/hosts");
        hostsSourceDao.insert(source3);

        HostsSource source4 = new HostsSource();
        source4.setLabel("SMhosts");
        source4.setUrl("https://raw.githubusercontent.com/2Gardon/SM-Ad-FuckU-hosts/refs/heads/master/SMAdHosts");
        hostsSourceDao.insert(source4);

        HostsSource source5 = new HostsSource();
        source5.setLabel("ADhosts");
        source5.setUrl("https://gitlab.com/rainmor/Adhosts-block/-/raw/master/hosts");
        hostsSourceDao.insert(source5);

        HostsSource source6 = new HostsSource();
        source6.setLabel("neohosts");
        source6.setUrl("https://cdn.jsdelivr.net/gh/neoFelhz/neohosts@gh-pages/127.0.0.1/full/hosts");
        hostsSourceDao.insert(source6);

        HostsSource source7 = new HostsSource();
        source7.setLabel("秋风hosts");
        source7.setUrl("https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/Filters/AWAvenue-Ads-Rule-hosts.txt");
        hostsSourceDao.insert(source7);
    }

    /**
     * Get the hosts source DAO.
     *
     * @return The hosts source DAO.
     */
    public abstract HostsSourceDao hostsSourceDao();

    /**
     * Get the hosts list item DAO.
     *
     * @return The hosts list item DAO.
     */
    public abstract HostListItemDao hostsListItemDao();

    /**
     * Get the hosts entry DAO.
     *
     * @return The hosts entry DAO.
     */
    public abstract HostEntryDao hostEntryDao();
}
