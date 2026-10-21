package org.adaway.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Embedded;

/** A browser entry retains its original source and scope, including disabled sources. */
public class RuleListRow {
    @Embedded public HostListItem item;
    @ColumnInfo(name = "source_label") public String sourceLabel;
    @ColumnInfo(name = "source_url") public String sourceUrl;
    @ColumnInfo(name = "source_enabled") public boolean sourceEnabled;

    public boolean isActive() { return sourceEnabled && item.isEnabled(); }
}
