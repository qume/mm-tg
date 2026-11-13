package org.telegram.mattermost;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

/**
 * Per-account Mattermost connection settings (server, session token, current user/team).
 */
public class MMConfig {

    private final SharedPreferences prefs;

    public String serverUrl;
    public String token;
    public String userId;
    public String username;
    public String teamId;

    public MMConfig(int account) {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("mmtg_account" + account, Context.MODE_PRIVATE);
        serverUrl = prefs.getString("server", null);
        token = prefs.getString("token", null);
        userId = prefs.getString("user_id", null);
        username = prefs.getString("username", null);
        teamId = prefs.getString("team_id", null);
    }

    public static String lastServer() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mmtg_global", Context.MODE_PRIVATE).getString("last_server", "");
    }

    public static void setLastServer(String server) {
        ApplicationLoader.applicationContext.getSharedPreferences("mmtg_global", Context.MODE_PRIVATE).edit().putString("last_server", server).apply();
    }

    public boolean isLoggedIn() {
        return !TextUtils.isEmpty(serverUrl) && !TextUtils.isEmpty(token) && !TextUtils.isEmpty(userId);
    }

    public void save() {
        prefs.edit()
                .putString("server", serverUrl)
                .putString("token", token)
                .putString("user_id", userId)
                .putString("username", username)
                .putString("team_id", teamId)
                .apply();
    }

    public void clear() {
        token = null;
        userId = null;
        username = null;
        teamId = null;
        prefs.edit().remove("token").remove("user_id").remove("username").remove("team_id").apply();
    }

    public static String normalizeServer(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.isEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        return s;
    }
}
