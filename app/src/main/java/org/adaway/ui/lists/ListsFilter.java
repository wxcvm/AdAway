package org.adaway.ui.lists;

/**
 * This class represents the filter to apply to host lists.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class ListsFilter {
    public static final ListsFilter ALL = new ListsFilter(true, "");
    /**
     * Whether included hosts from sources or not.
     */
    public final boolean sourcesIncluded;
    /**
     * The query filter to apply to hosts name (wildcard based).
     */
    public final String query;
    /**
     * The query filter to apply to hosts name (sql like format).
     */
    public final String sqlQuery;

    public ListsFilter(boolean sourcesIncluded, String query) {
        this.sourcesIncluded = sourcesIncluded;
        // BUG FIX: a malformed ACTION_SEARCH intent can deliver a null
        // query (see ListsActivity.handleQuery), which would NPE in
        // convertToLikeQuery(). Treat it as an empty filter.
        this.query = query == null ? "" : query;
        this.sqlQuery = convertToLikeQuery(this.query);
    }

    private static String convertToLikeQuery(String query) {
        return "%" + query.replace("*", "%")
                .replace("?", "_") + "%";
    }
}
