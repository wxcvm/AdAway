package org.adaway.model.backup;

import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.json.JSONException;
import org.json.JSONObject;

import static org.adaway.db.entity.HostsSource.USER_SOURCE_ID;

/**
 * This class defines user lists and hosts sources file format.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
final class BackupFormat {
    /*
     * Source backup format.
     */
    static final String SOURCES_KEY = "sources";
    static final String SOURCE_LABEL_ATTRIBUTE = "label";
    static final String SOURCE_URL_ATTRIBUTE = "url";
    static final String SOURCE_ENABLED_ATTRIBUTE = "enabled";
    static final String SOURCE_ALLOW_ATTRIBUTE = "allow";
    static final String SOURCE_REDIRECT_ATTRIBUTE = "redirect";
    /*
     * User source backup format.
     */
    static final String BLOCKED_KEY = "blocked";
    static final String ALLOWED_KEY = "allowed";
    static final String REDIRECTED_KEY = "redirected";
    static final String ENABLED_ATTRIBUTE = "enabled";
    static final String HOST_ATTRIBUTE = "host";
    static final String REDIRECT_ATTRIBUTE = "redirect";

    BackupFormat() {

    }

    static JSONObject sourceToJson(HostsSource source) throws JSONException {
        JSONObject sourceObject = new JSONObject();
        sourceObject.put(SOURCE_LABEL_ATTRIBUTE, source.getLabel());
        sourceObject.put(SOURCE_URL_ATTRIBUTE, source.getUrl());
        sourceObject.put(SOURCE_ENABLED_ATTRIBUTE, source.isEnabled());
        sourceObject.put(SOURCE_ALLOW_ATTRIBUTE, source.isAllowEnabled());
        sourceObject.put(SOURCE_REDIRECT_ATTRIBUTE, source.isRedirectEnabled());
        return sourceObject;
    }

    static HostsSource sourceFromJson(JSONObject sourceObject) throws JSONException {
        HostsSource source = new HostsSource();
        source.setLabel(sourceObject.getString(SOURCE_LABEL_ATTRIBUTE));
        String url = sourceObject.getString(SOURCE_URL_ATTRIBUTE);
        if (!HostsSource.isValidUrl(url)) {
            throw new JSONException("Invalid source URL: "+url);
        }
        source.setUrl(url);
        source.setEnabled(sourceObject.getBoolean(SOURCE_ENABLED_ATTRIBUTE));
        source.setAllowEnabled(sourceObject.getBoolean(SOURCE_ALLOW_ATTRIBUTE));
        source.setRedirectEnabled(sourceObject.getBoolean(SOURCE_REDIRECT_ATTRIBUTE));
        return source;
    }

    static JSONObject hostToJson(HostListItem host) throws JSONException {
        JSONObject hostObject = new JSONObject();
        hostObject.put(HOST_ATTRIBUTE, host.getHost());
        String redirection = host.getRedirection();
        if (redirection != null && !redirection.isEmpty()) {
            hostObject.put(REDIRECT_ATTRIBUTE, redirection);
        }
        hostObject.put(ENABLED_ATTRIBUTE, host.isEnabled());
        hostObject.put("match_scope", host.getMatchScope());
        hostObject.put("source_order", host.getSourceOrder());
        return hostObject;
    }

    static HostListItem hostFromJson(JSONObject hostObject) throws JSONException {
        HostListItem host = new HostListItem();
        host.setHost(hostObject.getString(HOST_ATTRIBUTE));
        if (hostObject.has(REDIRECT_ATTRIBUTE)) {
            host.setRedirection(hostObject.getString(REDIRECT_ATTRIBUTE));
        }
        host.setEnabled(hostObject.getBoolean(ENABLED_ATTRIBUTE));
        if (hostObject.has("match_scope")) {
            int scope = hostObject.getInt("match_scope");
            if (scope < -1 || scope > 2) throw new JSONException("Invalid match scope");
            host.setMatchScope(scope);
        }
        if (hostObject.has("source_order")) host.setSourceOrder(hostObject.getLong("source_order"));
        host.setSourceId(USER_SOURCE_ID);
        return host;
    }
}
