package org.telegram.mattermost;

import android.text.TextUtils;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers the Telegram UI's TL requests using a Mattermost server.
 * <p>
 * Mapping:
 * - 1:1 direct message channel  -> private chat with a user (self-DM -> Saved Messages)
 * - public/private/group channel -> megagroup (TL_channel)
 * - post -> message, root_id -> reply_to, file attachments -> photo/document media
 */
public class MMBackend {

    public interface Callback {
        void run(TLObject response, TLRPC.TL_error error);
    }

    private static final MMBackend[] instances = new MMBackend[16];

    public static MMBackend getInstance(int account) {
        MMBackend b = instances[account];
        if (b == null) {
            synchronized (MMBackend.class) {
                b = instances[account];
                if (b == null) {
                    instances[account] = b = new MMBackend(account);
                }
            }
        }
        return b;
    }

    private final int account;
    private MMConfig config;
    private volatile MMApi api;
    private volatile MMIds ids;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final ExecutorService filePool = Executors.newFixedThreadPool(6);
    private final ExecutorService fetchPool = Executors.newFixedThreadPool(8);
    private final Set<Integer> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedUnsupported = ConcurrentHashMap.newKeySet();

    // caches
    private final ConcurrentHashMap<String, JSONObject> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JSONObject> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JSONObject> members = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dmByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JSONObject> postCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JSONObject> fileInfos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<Integer, File>> uploads = new ConcurrentHashMap<>();
    private final Set<String> ownPendingPosts = ConcurrentHashMap.newKeySet();
    private final Set<String> favorites = ConcurrentHashMap.newKeySet();
    private final Object ptsLock = new Object();

    private WebSocket ws;
    private final AtomicInteger wsSeq = new AtomicInteger(1);
    private volatile boolean wsWanted;
    private int wsBackoff = 1;

    private MMBackend(int account) {
        this.account = account;
        reload();
    }

    private void reload() {
        config = new MMConfig(account);
        if (config.isLoggedIn()) {
            api = new MMApi(config.serverUrl, config.token);
        } else {
            api = null;
        }
    }

    private MMIds ids() {
        if (ids == null) {
            synchronized (this) {
                if (ids == null) {
                    ids = new MMIds(account);
                }
            }
        }
        return ids;
    }

    public MMConfig getConfig() {
        return config;
    }

    public boolean isCancelled(int token) {
        return cancelled.remove(token);
    }

    public void cancel(int token) {
        cancelled.add(token);
    }

    // ------------------------------------------------------------------------------------
    // Login / logout
    // ------------------------------------------------------------------------------------

    public interface LoginCallback {
        void onResult(TLRPC.User user, String error, boolean needMfa);
    }

    public void login(String server, String loginId, String password, String mfa, LoginCallback cb) {
        pool.execute(() -> {
            try {
                String srv = MMConfig.normalizeServer(server);
                MMApi a = new MMApi(srv, null);
                MMApi.LoginResult r = a.login(loginId, password, mfa);
                JSONArray teams = a.getArray("/users/me/teams");
                MMConfig c = new MMConfig(account);
                c.serverUrl = srv;
                c.token = r.token;
                c.userId = r.user.getString("id");
                c.username = r.user.optString("username");
                c.teamId = teams.length() > 0 ? teams.getJSONObject(0).getString("id") : null;
                c.save();
                MMConfig.setLastServer(srv);
                ids().clearAll();
                clearCaches();
                config = c;
                api = a;
                users.put(c.userId, r.user);
                TLRPC.User u = toUser(r.user);
                AndroidUtilities.runOnUIThread(() -> cb.onResult(u, null, false));
                startWebSocket();
            } catch (MMApi.MMException e) {
                boolean mfaNeeded = "mfa.validate_token.authenticate.app_error".equals(e.serverId) || (e.serverId != null && e.serverId.contains("mfa"));
                AndroidUtilities.runOnUIThread(() -> cb.onResult(null, e.getMessage(), mfaNeeded));
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> cb.onResult(null, e.getMessage() == null ? e.toString() : e.getMessage(), false));
            }
        });
    }

    private void clearCaches() {
        users.clear();
        statuses.clear();
        channels.clear();
        members.clear();
        dmByUser.clear();
        postCache.clear();
        fileInfos.clear();
        favorites.clear();
    }

    private void doLogout() {
        stopWebSocket();
        try {
            if (api != null) {
                api.post("/users/logout", null);
            }
        } catch (Exception ignore) {
        }
        config.clear();
        api = null;
        ids().clearAll();
        clearCaches();
    }

    // ------------------------------------------------------------------------------------
    // Request dispatch
    // ------------------------------------------------------------------------------------

    public void handle(TLObject req, int token, Callback cb) {
        ExecutorService ex = (req instanceof TLRPC.TL_upload_getFile || req instanceof TLRPC.TL_upload_saveFilePart || req instanceof TLRPC.TL_upload_saveBigFilePart) ? filePool : pool;
        ex.execute(() -> {
            TLObject res = null;
            TLRPC.TL_error err = null;
            try {
                res = dispatch(req);
                if (res == null) {
                    err = error(400, "MM_UNSUPPORTED");
                }
            } catch (MMApi.MMException e) {
                FileLog.e("mm request " + req + " failed: " + e.status + " " + e.getMessage());
                err = error(e.status == 403 ? 403 : 400, "MM_" + e.status + ": " + e.getMessage());
                if (e.status == 401 && api != null) {
                    onSessionExpired();
                }
            } catch (Throwable e) {
                FileLog.e(e);
                err = error(500, "MM_ERROR: " + e);
            } finally {
                try {
                    req.freeResources();
                } catch (Throwable ignore) {
                }
            }
            cb.run(res, err);
        });
    }

    private static TLRPC.TL_error error(int code, String text) {
        TLRPC.TL_error e = new TLRPC.TL_error();
        e.code = code;
        e.text = text;
        return e;
    }

    private void onSessionExpired() {
        FileLog.e("mm session expired, logging out");
        AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).performLogout(0));
    }

    private TLObject dispatch(TLObject req) throws Exception {
        if (req instanceof TLRPC.TL_auth_logOut) {
            doLogout();
            return new TLRPC.TL_auth_loggedOut();
        }
        if (api == null) {
            reload();
            if (api == null) {
                throw new MMApi.MMException(401, "Not logged in", null);
            }
        }
        if (wsWanted == false) {
            startWebSocket();
        }
        if (req instanceof TLRPC.TL_updates_getState) {
            TLRPC.TL_updates_state s = new TLRPC.TL_updates_state();
            s.pts = currentGlobalPts();
            s.date = now();
            s.seq = 0;
            s.qts = 0;
            return s;
        } else if (req instanceof TLRPC.TL_updates_getDifference) {
            return getDifference((TLRPC.TL_updates_getDifference) req);
        } else if (req instanceof TLRPC.TL_updates_getChannelDifference) {
            TLRPC.TL_updates_getChannelDifference r = (TLRPC.TL_updates_getChannelDifference) req;
            TLRPC.TL_updates_channelDifferenceEmpty d = new TLRPC.TL_updates_channelDifferenceEmpty();
            d.flags |= 1;
            d.isFinal = true;
            d.pts = Math.max(r.pts, channelPts(r.channel.channel_id));
            setChannelPts(r.channel.channel_id, d.pts);
            d.timeout = 60;
            return d;
        } else if (req instanceof TLRPC.TL_messages_getDialogs) {
            return getDialogs((TLRPC.TL_messages_getDialogs) req);
        } else if (req instanceof TLRPC.TL_messages_getPinnedDialogs) {
            TLRPC.TL_messages_peerDialogs r = new TLRPC.TL_messages_peerDialogs();
            r.state = new TLRPC.TL_updates_state();
            r.state.pts = currentGlobalPts();
            r.state.date = now();
            return r;
        } else if (req instanceof TLRPC.TL_messages_getPeerDialogs) {
            return getPeerDialogs((TLRPC.TL_messages_getPeerDialogs) req);
        } else if (req instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory r = (TLRPC.TL_messages_getHistory) req;
            return getHistory(r.peer, r.offset_id, r.add_offset, r.limit, r.max_id, r.min_id);
        } else if (req instanceof TLRPC.TL_messages_getMessages) {
            return getMessagesById(null, ((TLRPC.TL_messages_getMessages) req).id);
        } else if (req instanceof TLRPC.TL_channels_getMessages) {
            TLRPC.TL_channels_getMessages r = (TLRPC.TL_channels_getMessages) req;
            return getMessagesById(r.channel, r.id);
        } else if (req instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage r = (TLRPC.TL_messages_sendMessage) req;
            return sendPost(r.peer, r.reply_to, MMMarkdown.toMarkdown(r.message, r.entities), null, r.random_id);
        } else if (req instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia r = (TLRPC.TL_messages_sendMedia) req;
            return sendPost(r.peer, r.reply_to, MMMarkdown.toMarkdown(r.message, r.entities), r.media, r.random_id);
        } else if (req instanceof TLRPC.TL_messages_editMessage) {
            return editPost((TLRPC.TL_messages_editMessage) req);
        } else if (req instanceof TLRPC.TL_messages_deleteMessages) {
            return deletePosts(null, ((TLRPC.TL_messages_deleteMessages) req).id);
        } else if (req instanceof TLRPC.TL_channels_deleteMessages) {
            TLRPC.TL_channels_deleteMessages r = (TLRPC.TL_channels_deleteMessages) req;
            return deletePosts(r.channel, r.id);
        } else if (req instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory r = (TLRPC.TL_messages_readHistory) req;
            markViewed(channelForPeer(r.peer, false));
            TLRPC.TL_messages_affectedMessages a = new TLRPC.TL_messages_affectedMessages();
            a.pts = nextGlobalPts(1);
            a.pts_count = 1;
            return a;
        } else if (req instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory r = (TLRPC.TL_channels_readHistory) req;
            markViewed(ids().mm(r.channel.channel_id));
            return new TLRPC.TL_boolTrue();
        } else if (req instanceof TLRPC.TL_messages_setTyping) {
            TLRPC.TL_messages_setTyping r = (TLRPC.TL_messages_setTyping) req;
            if (r.action instanceof TLRPC.TL_sendMessageTypingAction) {
                String ch = channelForPeer(r.peer, false);
                if (ch != null) {
                    sendTyping(ch);
                }
            }
            return new TLRPC.TL_boolTrue();
        } else if (req instanceof TLRPC.TL_users_getUsers) {
            return getUsers(((TLRPC.TL_users_getUsers) req).id);
        } else if (req instanceof TLRPC.TL_users_getFullUser) {
            return getFullUser(((TLRPC.TL_users_getFullUser) req).id);
        } else if (req instanceof TLRPC.TL_channels_getFullChannel) {
            return getFullChannel(((TLRPC.TL_channels_getFullChannel) req).channel);
        } else if (req instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_messages_chats r = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel ic : ((TLRPC.TL_channels_getChannels) req).id) {
                JSONObject ch = channel(ids().mm(ic.channel_id));
                if (ch != null) {
                    r.chats.add(toChat(ch));
                }
            }
            return r;
        } else if (req instanceof TLRPC.TL_channels_getParticipants) {
            return getParticipants((TLRPC.TL_channels_getParticipants) req);
        } else if (req instanceof TLRPC.TL_contacts_getContacts) {
            return getContacts();
        } else if (req instanceof TLRPC.TL_contacts_search) {
            return searchPeers(((TLRPC.TL_contacts_search) req).q);
        } else if (req instanceof TLRPC.TL_contacts_resolveUsername) {
            return resolveUsername(((TLRPC.TL_contacts_resolveUsername) req).username);
        } else if (req instanceof TLRPC.TL_upload_getFile) {
            return getFile((TLRPC.TL_upload_getFile) req);
        } else if (req instanceof TLRPC.TL_upload_saveFilePart) {
            TLRPC.TL_upload_saveFilePart r = (TLRPC.TL_upload_saveFilePart) req;
            savePart(r.file_id, r.file_part, r.bytes);
            return new TLRPC.TL_boolTrue();
        } else if (req instanceof TLRPC.TL_upload_saveBigFilePart) {
            TLRPC.TL_upload_saveBigFilePart r = (TLRPC.TL_upload_saveBigFilePart) req;
            savePart(r.file_id, r.file_part, r.bytes);
            return new TLRPC.TL_boolTrue();
        } else if (req instanceof TLRPC.TL_messages_getPeerSettings) {
            TLRPC.TL_messages_peerSettings r = new TLRPC.TL_messages_peerSettings();
            r.settings = new TLRPC.TL_peerSettings();
            return r;
        } else if (req instanceof TLRPC.TL_help_getAppConfig) {
            TLRPC.TL_help_appConfig r = new TLRPC.TL_help_appConfig();
            r.config = new TLRPC.TL_jsonObject();
            return r;
        } else if (req instanceof TLRPC.TL_messages_getDialogFilters) {
            TLRPC.TL_messages_dialogFilters r = new TLRPC.TL_messages_dialogFilters();
            return r;
        } else if (req instanceof org.telegram.tgnet.tl.TL_account.updateStatus || req instanceof TLRPC.TL_messages_reorderPinnedDialogs) {
            return new TLRPC.TL_boolTrue();
        }
        String name = req.getClass().getSimpleName();
        if (loggedUnsupported.add(name)) {
            FileLog.d("mm unsupported request: " + name);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // pts bookkeeping (Telegram's update sequencing)
    // ------------------------------------------------------------------------------------

    private int currentGlobalPts() {
        synchronized (ptsLock) {
            String v = ids().getKv("gpts");
            return v == null ? 1 : Integer.parseInt(v);
        }
    }

    private int nextGlobalPts(int count) {
        synchronized (ptsLock) {
            int p = currentGlobalPts() + count;
            ids().putKv("gpts", String.valueOf(p));
            return p;
        }
    }

    private int channelPts(long chatId) {
        synchronized (ptsLock) {
            String v = ids().getKv("cpts_" + chatId);
            return v == null ? 1 : Integer.parseInt(v);
        }
    }

    private void setChannelPts(long chatId, int pts) {
        synchronized (ptsLock) {
            ids().putKv("cpts_" + chatId, String.valueOf(pts));
        }
    }

    private int nextChannelPts(long chatId, int count) {
        synchronized (ptsLock) {
            int p = channelPts(chatId) + count;
            setChannelPts(chatId, p);
            return p;
        }
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    // ------------------------------------------------------------------------------------
    // Entity caches
    // ------------------------------------------------------------------------------------

    private JSONObject user(String id) throws Exception {
        if (id == null) {
            return null;
        }
        JSONObject u = users.get(id);
        if (u == null) {
            u = api.getObject("/users/" + id);
            users.put(id, u);
        }
        return u;
    }

    private void ensureUsers(Set<String> needed) throws Exception {
        JSONArray missing = new JSONArray();
        for (String id : needed) {
            if (id != null && !id.isEmpty() && !users.containsKey(id)) {
                missing.put(id);
            }
        }
        for (int off = 0; off < missing.length(); off += 100) {
            JSONArray chunk = new JSONArray();
            for (int i = off; i < Math.min(missing.length(), off + 100); i++) {
                chunk.put(missing.get(i));
            }
            Object res = api.post("/users/ids", chunk);
            if (res instanceof JSONArray) {
                JSONArray arr = (JSONArray) res;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.getJSONObject(i);
                    users.put(u.getString("id"), u);
                }
            }
        }
    }

    private void loadStatuses(Set<String> userIds) {
        try {
            JSONArray arr = new JSONArray();
            for (String id : userIds) {
                arr.put(id);
            }
            if (arr.length() == 0) {
                return;
            }
            Object res = api.post("/users/status/ids", arr);
            if (res instanceof JSONArray) {
                JSONArray a = (JSONArray) res;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject s = a.getJSONObject(i);
                    statuses.put(s.getString("user_id"), s.optString("status"));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private JSONObject channel(String id) throws Exception {
        if (id == null) {
            return null;
        }
        JSONObject c = channels.get(id);
        if (c == null) {
            c = api.getObject("/channels/" + id);
            putChannel(c);
        }
        return c;
    }

    private void putChannel(JSONObject c) {
        String id = c.optString("id");
        channels.put(id, c);
        if ("D".equals(c.optString("type"))) {
            String other = dmOther(c);
            if (other != null) {
                dmByUser.put(other, id);
            }
        }
    }

    /**
     * For a DM channel, the id of the other participant (or self for a self-DM).
     */
    private String dmOther(JSONObject c) {
        String name = c.optString("name");
        int idx = name.indexOf("__");
        if (idx < 0) {
            return null;
        }
        String a = name.substring(0, idx);
        String b = name.substring(idx + 2);
        return a.equals(config.userId) ? b : a;
    }

    private boolean isDm(JSONObject c) {
        return "D".equals(c.optString("type"));
    }

    /**
     * Telegram dialog id for a Mattermost channel.
     */
    private long dialogId(JSONObject c) {
        if (isDm(c)) {
            return ids().userId(dmOther(c));
        }
        return -ids().chatId(c.optString("id"));
    }

    private String channelForPeer(TLRPC.InputPeer peer, boolean create) throws Exception {
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            return ids().mm(peer.channel_id);
        }
        String userMm;
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            userMm = config.userId;
        } else if (peer instanceof TLRPC.TL_inputPeerUser) {
            userMm = ids().mm(peer.user_id);
        } else {
            return null;
        }
        return dmChannelFor(userMm, create);
    }

    private String dmChannelFor(String userMm, boolean create) throws Exception {
        if (userMm == null) {
            return null;
        }
        String ch = dmByUser.get(userMm);
        if (ch != null) {
            return ch;
        }
        // DM channel names are the two user ids sorted, joined with "__"
        String a = config.userId, b = userMm;
        String name = a.compareTo(b) < 0 ? a + "__" + b : b + "__" + a;
        if (config.teamId != null) {
            try {
                JSONObject c = api.getObject("/teams/" + config.teamId + "/channels/name/" + MMApi.enc(name) + "?include_deleted=true");
                putChannel(c);
                return c.optString("id");
            } catch (MMApi.MMException ignore) {
            }
        }
        if (!create) {
            return null;
        }
        JSONArray body = new JSONArray();
        body.put(config.userId);
        body.put(userMm);
        JSONObject c = (JSONObject) api.post("/channels/direct", body);
        putChannel(c);
        return c.optString("id");
    }

    private long peerDialogId(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            return -peer.channel_id;
        } else if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return ids().userId(config.userId);
        } else {
            return peer.user_id;
        }
    }

    // ------------------------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------------------------

    private TLRPC.User toUser(JSONObject u) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        String id = u.optString("id");
        user.id = ids().userId(id);
        String first = u.optString("first_name", "");
        String last = u.optString("last_name", "");
        String nick = u.optString("nickname", "");
        String username = u.optString("username", "");
        if (first.isEmpty() && last.isEmpty()) {
            first = !nick.isEmpty() ? nick : username;
        }
        user.first_name = first;
        user.flags |= TLObject.FLAG_1;
        if (!last.isEmpty()) {
            user.last_name = last;
            user.flags |= TLObject.FLAG_2;
        }
        if (!username.isEmpty()) {
            user.username = username;
            user.flags |= TLObject.FLAG_3;
        }
        user.self = id.equals(config.userId);
        user.contact = true;
        user.mutual_contact = true;
        user.bot = u.optBoolean("is_bot", false);
        user.deleted = u.optLong("delete_at", 0) > 0;
        long pic = u.optLong("last_picture_update", 0);
        if (pic > 0) {
            TLRPC.TL_userProfilePhoto p = new TLRPC.TL_userProfilePhoto();
            p.photo_id = ids().id(MMIds.KIND_FILE, "avatar:" + id) * 1000 + Math.abs(pic % 1000);
            p.dc_id = 2;
            p.flags = 0;
            user.photo = p;
            user.flags |= TLObject.FLAG_5;
        } else {
            user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        }
        String st = statuses.get(id);
        if (st != null) {
            if ("online".equals(st) || "dnd".equals(st)) {
                TLRPC.TL_userStatusOnline s = new TLRPC.TL_userStatusOnline();
                s.expires = now() + 300;
                user.status = s;
            } else if ("away".equals(st)) {
                user.status = new TLRPC.TL_userStatusRecently();
            } else {
                user.status = new TLRPC.TL_userStatusRecently();
            }
        } else {
            user.status = new TLRPC.TL_userStatusRecently();
        }
        user.flags |= TLObject.FLAG_6;
        return user;
    }

    private String channelTitle(JSONObject c) {
        String t = c.optString("display_name");
        if (TextUtils.isEmpty(t)) {
            t = c.optString("name");
        }
        if ("G".equals(c.optString("type"))) {
            // group message display_name is a comma separated list of usernames
            t = t.replace(config.username + ", ", "").replace(", " + config.username, "");
        }
        return t;
    }

    private TLRPC.Chat toChat(JSONObject c) {
        TLRPC.TL_channel ch = new TLRPC.TL_channel();
        ch.id = ids().chatId(c.optString("id"));
        ch.title = channelTitle(c);
        ch.megagroup = true;
        ch.date = (int) (c.optLong("create_at") / 1000);
        ch.photo = new TLRPC.TL_chatPhotoEmpty();
        ch.access_hash = 0;
        ch.flags |= TLObject.FLAG_13;
        ch.left = false;
        ch.creator = config.userId.equals(c.optString("creator_id"));
        long mc = c.optLong("member_count", 0);
        if (mc > 0) {
            ch.participants_count = (int) mc;
            ch.flags |= TLObject.FLAG_17;
        }
        return ch;
    }

    private TLRPC.Peer peerFor(long did) {
        if (did > 0) {
            TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
            p.user_id = did;
            return p;
        } else {
            TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
            p.channel_id = -did;
            return p;
        }
    }

    private int midFor(long did, JSONObject post) {
        return ids().mid(did, post.optString("id"), post.optLong("create_at"));
    }

    private JSONObject postById(String id, JSONObject batch) throws Exception {
        JSONObject p = batch != null ? batch.optJSONObject(id) : null;
        if (p == null) {
            p = postCache.get(id);
        }
        if (p == null) {
            p = api.getObject("/posts/" + id);
            postCache.put(id, p);
        }
        return p;
    }

    private static boolean isSystemPost(JSONObject post) {
        String t = post.optString("type", "");
        return t.startsWith("system_");
    }

    private String systemText(JSONObject post) {
        String msg = post.optString("message", "");
        if (!msg.isEmpty()) {
            return msg;
        }
        return post.optString("type").replace("system_", "").replace('_', ' ');
    }

    /**
     * Convert a Mattermost post to a Telegram message.
     *
     * @param batch optional "posts" map from the same response (used to resolve thread roots)
     */
    private TLRPC.Message toMessage(JSONObject post, JSONObject channel, JSONObject batch) throws Exception {
        long did = dialogId(channel);
        int mid = midFor(did, post);
        String userId = post.optString("user_id");
        boolean out = userId.equals(config.userId);
        TLRPC.Message m;
        if (isSystemPost(post)) {
            TLRPC.TL_messageService s = new TLRPC.TL_messageService();
            TLRPC.TL_messageActionCustomAction a = new TLRPC.TL_messageActionCustomAction();
            a.message = systemText(post);
            s.action = a;
            m = s;
        } else {
            TLRPC.TL_message tm = new TLRPC.TL_message();
            MMMarkdown.Result md = MMMarkdown.parse(post.optString("message", ""));
            tm.message = md.text;
            tm.entities = md.entities;
            if (!md.entities.isEmpty()) {
                tm.flags |= TLObject.FLAG_7;
            }
            m = tm;
            String root = post.optString("root_id", "");
            if (!root.isEmpty()) {
                try {
                    JSONObject rp = postById(root, batch);
                    TLRPC.TL_messageReplyHeader h = new TLRPC.TL_messageReplyHeader();
                    h.reply_to_msg_id = midFor(did, rp);
                    h.flags |= TLObject.FLAG_4;
                    m.reply_to = h;
                    m.flags |= TLObject.FLAG_3;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            JSONObject meta = post.optJSONObject("metadata");
            JSONArray files = meta != null ? meta.optJSONArray("files") : null;
            if (files != null && files.length() > 0) {
                JSONObject f = files.getJSONObject(0);
                m.media = toMedia(f, post.optLong("create_at"));
                m.flags |= TLObject.FLAG_9;
                if (files.length() > 1) {
                    StringBuilder extra = new StringBuilder(m.message);
                    for (int i = 1; i < files.length(); i++) {
                        JSONObject ef = files.getJSONObject(i);
                        fileInfos.put(ef.optString("id"), ef);
                        if (extra.length() > 0) {
                            extra.append('\n');
                        }
                        extra.append("📎 ").append(ef.optString("name"));
                    }
                    m.message = extra.toString();
                }
            }
            if (post.optLong("edit_at", 0) > 0) {
                m.edit_date = (int) (post.optLong("edit_at") / 1000);
                m.flags |= TLObject.FLAG_15;
            }
            m.pinned = post.optBoolean("is_pinned", false);
        }
        m.id = mid;
        m.local_id = mid;
        m.date = (int) (post.optLong("create_at") / 1000);
        m.peer_id = peerFor(did);
        m.dialog_id = did;
        TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
        from.user_id = ids().userId(userId);
        m.from_id = from;
        m.flags |= TLObject.FLAG_8;
        m.out = out;
        m.unread = false;
        if (m.message == null) {
            m.message = "";
        }
        return m;
    }

    private TLRPC.MessageMedia toMedia(JSONObject f, long date) {
        String id = f.optString("id");
        fileInfos.put(id, f);
        String mime = f.optString("mime_type", "application/octet-stream");
        int w = f.optInt("width", 0);
        int h = f.optInt("height", 0);
        boolean hasPreview = f.optBoolean("has_preview_image", false);
        long size = f.optLong("size", 0);
        if (mime.startsWith("image/") && hasPreview && w > 0 && h > 0 && !mime.contains("gif")) {
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = ids().fileId(id);
            photo.access_hash = 0;
            photo.file_reference = new byte[0];
            photo.date = (int) (date / 1000);
            photo.dc_id = 2;
            TLRPC.TL_photoSize small = new TLRPC.TL_photoSize();
            small.type = "s";
            float ts = Math.min(120f / w, 100f / h);
            small.w = Math.max(1, (int) (w * Math.min(1f, ts)));
            small.h = Math.max(1, (int) (h * Math.min(1f, ts)));
            small.size = 0;
            small.location = new TLRPC.TL_fileLocationUnavailable();
            photo.sizes.add(small);
            TLRPC.TL_photoSize big = new TLRPC.TL_photoSize();
            if (w > 1920 || h > 1080) {
                float s = Math.min(1920f / w, 1080f / h);
                big.type = "x";
                big.w = (int) (w * s);
                big.h = (int) (h * s);
                big.size = 0;
            } else {
                big.type = "y";
                big.w = w;
                big.h = h;
                big.size = (int) size;
            }
            big.location = new TLRPC.TL_fileLocationUnavailable();
            photo.sizes.add(big);
            TLRPC.TL_messageMediaPhoto mp = new TLRPC.TL_messageMediaPhoto();
            mp.photo = photo;
            mp.flags |= TLObject.FLAG_0;
            return mp;
        }
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = ids().fileId(id);
        doc.access_hash = 0;
        doc.file_reference = new byte[0];
        doc.date = (int) (date / 1000);
        doc.mime_type = mime;
        doc.size = size;
        doc.dc_id = 2;
        TLRPC.TL_documentAttributeFilename fn = new TLRPC.TL_documentAttributeFilename();
        fn.file_name = f.optString("name", "file");
        doc.attributes.add(fn);
        if (mime.startsWith("image/") && w > 0 && h > 0) {
            TLRPC.TL_documentAttributeImageSize is = new TLRPC.TL_documentAttributeImageSize();
            is.w = w;
            is.h = h;
            doc.attributes.add(is);
        }
        if (hasPreview && w > 0 && h > 0) {
            TLRPC.TL_photoSize th = new TLRPC.TL_photoSize();
            th.type = "s";
            float ts = Math.min(1f, Math.min(120f / w, 100f / h));
            th.w = Math.max(1, (int) (w * ts));
            th.h = Math.max(1, (int) (h * ts));
            th.size = 0;
            th.location = new TLRPC.TL_fileLocationUnavailable();
            doc.thumbs.add(th);
            doc.flags |= TLObject.FLAG_0;
        }
        TLRPC.TL_messageMediaDocument md = new TLRPC.TL_messageMediaDocument();
        md.document = doc;
        md.flags |= TLObject.FLAG_0;
        return md;
    }

    private TLRPC.PeerNotifySettings notifySettings(JSONObject member) {
        TLRPC.TL_peerNotifySettings s = new TLRPC.TL_peerNotifySettings();
        if (member != null) {
            JSONObject np = member.optJSONObject("notify_props");
            if (np != null && "mention".equals(np.optString("mark_unread"))) {
                s.mute_until = Integer.MAX_VALUE;
                s.flags |= TLObject.FLAG_2;
            }
        }
        return s;
    }

    // ------------------------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------------------------

    private List<JSONObject> loadMyChannels() throws Exception {
        JSONArray arr;
        try {
            arr = api.getArray("/users/me/channels?include_deleted=false&per_page=1000");
        } catch (MMApi.MMException e) {
            // older servers: per-team
            arr = new JSONArray();
            JSONArray teams = api.getArray("/users/me/teams");
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < teams.length(); i++) {
                JSONArray tc = api.getArray("/users/me/teams/" + teams.getJSONObject(i).getString("id") + "/channels");
                for (int j = 0; j < tc.length(); j++) {
                    JSONObject c = tc.getJSONObject(j);
                    if (seen.add(c.optString("id"))) {
                        arr.put(c);
                    }
                }
            }
        }
        JSONArray mem;
        try {
            mem = api.getArray("/users/me/channel_members?per_page=1000");
        } catch (MMApi.MMException e) {
            mem = new JSONArray();
            JSONArray teams = api.getArray("/users/me/teams");
            for (int i = 0; i < teams.length(); i++) {
                JSONArray tm = api.getArray("/users/me/teams/" + teams.getJSONObject(i).getString("id") + "/channels/members");
                for (int j = 0; j < tm.length(); j++) {
                    mem.put(tm.get(j));
                }
            }
        }
        for (int i = 0; i < mem.length(); i++) {
            JSONObject m = mem.getJSONObject(i);
            members.put(m.optString("channel_id"), m);
        }
        // hidden DMs / GMs and favorites
        HashSet<String> hidden = new HashSet<>();
        favorites.clear();
        try {
            JSONArray prefs = api.getArray("/users/me/preferences");
            for (int i = 0; i < prefs.length(); i++) {
                JSONObject p = prefs.getJSONObject(i);
                String cat = p.optString("category");
                if (("direct_channel_show".equals(cat) || "group_channel_show".equals(cat)) && "false".equals(p.optString("value"))) {
                    hidden.add(p.optString("name"));
                } else if ("favorite_channel".equals(cat) && "true".equals(p.optString("value"))) {
                    favorites.add(p.optString("name"));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        ArrayList<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            if (c.optLong("delete_at", 0) > 0) {
                continue;
            }
            putChannel(c);
            String type = c.optString("type");
            if ("D".equals(type)) {
                String other = dmOther(c);
                if (other == null || (hidden.contains(other) && !favorites.contains(c.optString("id")))) {
                    continue;
                }
                if (c.optLong("total_msg_count", 0) == 0 && !other.equals(config.userId)) {
                    continue;
                }
            } else if ("G".equals(type)) {
                if (hidden.contains(c.optString("id"))) {
                    continue;
                }
            }
            result.add(c);
        }
        Collections.sort(result, (a, b) -> Long.compare(lastActivity(b), lastActivity(a)));
        return result;
    }

    private static long lastActivity(JSONObject c) {
        return Math.max(c.optLong("last_post_at", 0), c.optLong("create_at", 0));
    }

    private static class DialogBundle {
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        ArrayList<TLRPC.User> users = new ArrayList<>();
    }

    private final ConcurrentHashMap<String, JSONObject> lastPostCache = new ConcurrentHashMap<>();

    private JSONObject lastPost(JSONObject c) throws Exception {
        String key = c.optString("id") + "@" + c.optLong("last_post_at");
        JSONObject cached = lastPostCache.get(key);
        if (cached != null) {
            return cached;
        }
        JSONObject res = api.getObject("/channels/" + c.optString("id") + "/posts?page=0&per_page=1");
        JSONArray order = res.optJSONArray("order");
        JSONObject posts = res.optJSONObject("posts");
        JSONObject p = null;
        if (order != null && order.length() > 0 && posts != null) {
            p = posts.optJSONObject(order.getString(0));
            JSONObject finalPosts = posts;
            if (p != null) {
                p.put("__batch", finalPosts);
            }
        }
        if (p == null) {
            p = new JSONObject();
        }
        lastPostCache.put(key, p);
        return p;
    }

    private DialogBundle buildDialogs(List<JSONObject> chans) throws Exception {
        DialogBundle b = new DialogBundle();
        // fetch last posts in parallel
        ArrayList<Future<JSONObject>> futures = new ArrayList<>();
        for (JSONObject c : chans) {
            futures.add(fetchPool.submit(() -> {
                try {
                    return lastPost(c);
                } catch (Exception e) {
                    FileLog.e(e);
                    return new JSONObject();
                }
            }));
        }
        HashSet<String> needUsers = new HashSet<>();
        needUsers.add(config.userId);
        ArrayList<JSONObject> lastPosts = new ArrayList<>();
        for (int i = 0; i < chans.size(); i++) {
            JSONObject p = futures.get(i).get();
            lastPosts.add(p);
            JSONObject c = chans.get(i);
            if (isDm(c)) {
                needUsers.add(dmOther(c));
            }
            if (p.has("user_id")) {
                needUsers.add(p.optString("user_id"));
            }
        }
        ensureUsers(needUsers);
        HashSet<String> dmUsers = new HashSet<>();
        for (JSONObject c : chans) {
            if (isDm(c)) {
                dmUsers.add(dmOther(c));
            }
        }
        loadStatuses(dmUsers);
        for (String uid : needUsers) {
            JSONObject u = users.get(uid);
            if (u != null) {
                b.users.add(toUser(u));
            }
        }
        for (int i = 0; i < chans.size(); i++) {
            JSONObject c = chans.get(i);
            JSONObject p = lastPosts.get(i);
            JSONObject member = members.get(c.optString("id"));
            long did = dialogId(c);
            TLRPC.TL_dialog d = new TLRPC.TL_dialog();
            d.peer = peerFor(did);
            d.id = did;
            if (p.has("id")) {
                TLRPC.Message m = toMessage(p, c, p.optJSONObject("__batch"));
                b.messages.add(m);
                d.top_message = m.id;
                d.last_message_date = m.date;
            } else {
                d.top_message = 0;
                d.last_message_date = (int) (c.optLong("create_at") / 1000);
            }
            long lastViewed = member != null ? member.optLong("last_viewed_at", 0) : 0;
            d.read_inbox_max_id = lastViewed > 0 ? MMIds.timeToMid(lastViewed) : 0;
            if (d.top_message != 0 && p.has("create_at") && p.optLong("create_at") <= lastViewed) {
                d.read_inbox_max_id = Math.max(d.read_inbox_max_id, d.top_message);
            }
            d.read_outbox_max_id = d.top_message;
            long unread = 0;
            if (member != null) {
                unread = c.optLong("total_msg_count", 0) - member.optLong("msg_count", 0);
                d.unread_mentions_count = member.optInt("mention_count", 0);
            }
            d.unread_count = (int) Math.max(0, unread);
            if (d.unread_count == 0 && d.top_message > d.read_inbox_max_id) {
                d.read_inbox_max_id = d.top_message;
            }
            d.notify_settings = notifySettings(member);
            d.pinned = favorites.contains(c.optString("id"));
            if (did < 0) {
                d.pts = channelPts(-did);
                d.flags |= TLObject.FLAG_0;
                b.chats.add(toChat(c));
            }
            b.dialogs.add(d);
        }
        return b;
    }

    private TLObject getDialogs(TLRPC.TL_messages_getDialogs req) throws Exception {
        TLRPC.TL_messages_dialogs r = new TLRPC.TL_messages_dialogs();
        if (req.folder_id != 0 || req.offset_date != 0 || req.offset_id != 0) {
            return r;
        }
        DialogBundle b = buildDialogs(loadMyChannels());
        r.dialogs = b.dialogs;
        r.messages = b.messages;
        r.chats = b.chats;
        r.users = b.users;
        r.count = b.dialogs.size();
        return r;
    }

    private TLObject getPeerDialogs(TLRPC.TL_messages_getPeerDialogs req) throws Exception {
        ArrayList<JSONObject> chans = new ArrayList<>();
        for (TLRPC.InputDialogPeer idp : req.peers) {
            if (idp instanceof TLRPC.TL_inputDialogPeer) {
                String ch = channelForPeer(((TLRPC.TL_inputDialogPeer) idp).peer, false);
                if (ch != null) {
                    JSONObject c = api.getObject("/channels/" + ch);
                    putChannel(c);
                    try {
                        members.put(ch, api.getObject("/channels/" + ch + "/members/me"));
                    } catch (Exception ignore) {
                    }
                    chans.add(c);
                }
            }
        }
        DialogBundle b = buildDialogs(chans);
        TLRPC.TL_messages_peerDialogs r = new TLRPC.TL_messages_peerDialogs();
        r.dialogs = b.dialogs;
        r.messages = b.messages;
        r.chats = b.chats;
        r.users = b.users;
        r.state = new TLRPC.TL_updates_state();
        r.state.pts = currentGlobalPts();
        r.state.date = now();
        return r;
    }

    // ------------------------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------------------------

    private void collectPosts(JSONObject res, ArrayList<JSONObject> out) throws Exception {
        JSONArray order = res.optJSONArray("order");
        JSONObject posts = res.optJSONObject("posts");
        if (order == null || posts == null) {
            return;
        }
        for (int i = 0; i < order.length(); i++) {
            JSONObject p = posts.optJSONObject(order.getString(i));
            if (p != null) {
                p.put("__batch", posts);
                out.add(p);
            }
        }
    }

    private TLObject getHistory(TLRPC.InputPeer peer, int offsetId, int addOffset, int limit, int maxId, int minId) throws Exception {
        long did = peerDialogId(peer);
        String chId = channelForPeer(peer, false);
        ArrayList<JSONObject> posts = new ArrayList<>(); // newest first
        JSONObject channel = chId != null ? channel(chId) : null;
        if (channel != null) {
            if (limit <= 0) {
                limit = 20;
            }
            String anchor = offsetId > 0 ? ids().postAtOrBefore(did, offsetId) : null;
            if (offsetId > 0 && anchor == null) {
                // unknown anchor: approximate by time
                long t = MMIds.midToTime(offsetId);
                JSONObject res = api.getObject("/channels/" + chId + "/posts?since=" + (t - 1) + "&per_page=" + limit);
                collectPosts(res, posts);
            } else if (anchor == null) {
                JSONObject res = api.getObject("/channels/" + chId + "/posts?page=0&per_page=" + limit);
                collectPosts(res, posts);
            } else {
                int newer = Math.max(0, -addOffset);
                int older = Math.max(0, limit + Math.min(0, addOffset));
                ArrayList<JSONObject> after = new ArrayList<>();
                if (newer > 0) {
                    JSONObject res = api.getObject("/channels/" + chId + "/posts?after=" + anchor + "&per_page=" + newer);
                    collectPosts(res, after);
                }
                ArrayList<JSONObject> anchorList = new ArrayList<>();
                int anchorMid = ids().existingMid(anchor);
                if (newer > 0 && anchorMid == offsetId) {
                    JSONObject ap = api.getObject("/posts/" + anchor);
                    anchorList.add(ap);
                    older = Math.max(0, older - 1);
                }
                ArrayList<JSONObject> before = new ArrayList<>();
                if (older > 0) {
                    String beforeAnchor = anchor;
                    if (anchorMid < offsetId && newer == 0) {
                        // anchor itself is older than offset: include it
                        anchorList.add(api.getObject("/posts/" + anchor));
                        older--;
                    }
                    if (older > 0) {
                        JSONObject res = api.getObject("/channels/" + chId + "/posts?before=" + beforeAnchor + "&per_page=" + older);
                        collectPosts(res, before);
                    }
                }
                posts.addAll(after);
                posts.addAll(anchorList);
                posts.addAll(before);
            }
        }
        HashSet<String> needUsers = new HashSet<>();
        for (JSONObject p : posts) {
            needUsers.add(p.optString("user_id"));
        }
        if (channel != null && isDm(channel)) {
            needUsers.add(dmOther(channel));
        }
        ensureUsers(needUsers);
        messages_Messages_Builder builder = new messages_Messages_Builder(did < 0);
        HashSet<Integer> seen = new HashSet<>();
        for (JSONObject p : posts) {
            TLRPC.Message m = toMessage(p, channel, p.optJSONObject("__batch"));
            if ((maxId > 0 && m.id >= maxId) || (minId > 0 && m.id <= minId) || !seen.add(m.id)) {
                continue;
            }
            builder.r.messages.add(m);
        }
        Collections.sort(builder.r.messages, (a, b) -> Integer.compare(b.id, a.id));
        for (String uid : needUsers) {
            JSONObject u = users.get(uid);
            if (u != null) {
                builder.r.users.add(toUser(u));
            }
        }
        if (channel != null && !isDm(channel)) {
            builder.r.chats.add(toChat(channel));
            builder.r.pts = channelPts(-did);
        }
        builder.r.count = builder.r.messages.size();
        return builder.r;
    }

    private static class messages_Messages_Builder {
        final TLRPC.messages_Messages r;

        messages_Messages_Builder(boolean channel) {
            r = channel ? new TLRPC.TL_messages_channelMessages() : new TLRPC.TL_messages_messages();
        }
    }

    private TLObject getMessagesById(TLRPC.InputChannel channel, ArrayList<Integer> mids) throws Exception {
        messages_Messages_Builder builder = new messages_Messages_Builder(channel != null);
        HashSet<String> needUsers = new HashSet<>();
        ArrayList<JSONObject[]> found = new ArrayList<>();
        for (Integer mid : mids) {
            String postId = null;
            if (channel != null) {
                postId = ids().postAt(-channel.channel_id, mid);
            } else {
                postId = ids().postAtUserSpace(mid);
            }
            if (postId == null) {
                continue;
            }
            try {
                JSONObject p = postById(postId, null);
                JSONObject c = channel(p.optString("channel_id"));
                found.add(new JSONObject[]{p, c});
                needUsers.add(p.optString("user_id"));
            } catch (MMApi.MMException e) {
                // deleted
            }
        }
        ensureUsers(needUsers);
        for (JSONObject[] pc : found) {
            builder.r.messages.add(toMessage(pc[0], pc[1], null));
            if (!isDm(pc[1])) {
                builder.r.chats.add(toChat(pc[1]));
            }
        }
        for (String uid : needUsers) {
            JSONObject u = users.get(uid);
            if (u != null) {
                builder.r.users.add(toUser(u));
            }
        }
        builder.r.count = builder.r.messages.size();
        return builder.r;
    }

    // ------------------------------------------------------------------------------------
    // Sending / editing / deleting
    // ------------------------------------------------------------------------------------

    private File uploadDir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "mm_uploads");
        d.mkdirs();
        return d;
    }

    private void savePart(long fileId, int part, NativeByteBuffer bytes) throws Exception {
        bytes.rewind();
        int len = bytes.limit();
        byte[] data = new byte[len];
        bytes.buffer.get(data, 0, len);
        File f = new File(uploadDir(), fileId + "_" + part);
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.close();
        Map<Integer, File> parts = uploads.get(fileId);
        if (parts == null) {
            parts = new ConcurrentHashMap<>();
            Map<Integer, File> prev = uploads.putIfAbsent(fileId, parts);
            if (prev != null) {
                parts = prev;
            }
        }
        parts.put(part, f);
    }

    private File assemble(TLRPC.InputFile inputFile) throws Exception {
        Map<Integer, File> parts = uploads.remove(inputFile.id);
        if (parts == null) {
            throw new MMApi.MMException(400, "Upload parts missing", null);
        }
        File out = new File(uploadDir(), "assembled_" + inputFile.id);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        for (int i = 0; i < inputFile.parts; i++) {
            File p = parts.get(i);
            if (p == null) {
                fos.close();
                throw new MMApi.MMException(400, "Upload part " + i + " missing", null);
            }
            FileInputStream fis = new FileInputStream(p);
            int r;
            while ((r = fis.read(buf)) > 0) {
                fos.write(buf, 0, r);
            }
            fis.close();
            p.delete();
        }
        fos.close();
        return out;
    }

    private static String extForMime(String mime) {
        if (mime == null) {
            return "";
        }
        switch (mime) {
            case "image/jpeg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "video/mp4":
                return ".mp4";
            case "audio/ogg":
                return ".ogg";
            case "audio/mpeg":
                return ".mp3";
            default:
                return "";
        }
    }

    private TLObject sendPost(TLRPC.InputPeer peer, TLRPC.InputReplyTo replyTo, String text, TLRPC.InputMedia media, long randomId) throws Exception {
        String chId = channelForPeer(peer, true);
        if (chId == null) {
            throw new MMApi.MMException(400, "Unknown chat", null);
        }
        JSONObject channel = channel(chId);
        long did = dialogId(channel);
        JSONObject body = new JSONObject();
        body.put("channel_id", chId);
        body.put("message", text == null ? "" : text);
        String pending = config.userId + ":" + System.currentTimeMillis() + ":" + randomId;
        body.put("pending_post_id", pending);
        if (replyTo instanceof TLRPC.TL_inputReplyToMessage && replyTo.reply_to_msg_id != 0) {
            String target = ids().postAt(did, replyTo.reply_to_msg_id);
            if (target != null) {
                JSONObject tp = postById(target, null);
                String root = tp.optString("root_id", "");
                body.put("root_id", root.isEmpty() ? target : root);
            }
        }
        if (media != null) {
            if (media.file != null) {
                File f = assemble(media.file);
                String name = media.file.name;
                String mime = media.mime_type;
                if (media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
                    mime = "image/jpeg";
                    if (name == null || name.isEmpty() || !name.contains(".")) {
                        name = "photo_" + System.currentTimeMillis() + ".jpg";
                    }
                } else {
                    for (TLRPC.DocumentAttribute a : media.attributes) {
                        if (a instanceof TLRPC.TL_documentAttributeFilename) {
                            name = a.file_name;
                        }
                    }
                    if (name == null || name.isEmpty()) {
                        name = "file_" + System.currentTimeMillis() + extForMime(mime);
                    }
                }
                JSONObject info = api.uploadFile(chId, f, name, mime);
                f.delete();
                if (media.thumb != null) {
                    uploads.remove(media.thumb.id);
                }
                JSONArray fileIds = new JSONArray();
                fileIds.put(info.getString("id"));
                body.put("file_ids", fileIds);
            } else {
                throw new MMApi.MMException(400, "Forwarding media is not supported yet", null);
            }
        }
        ownPendingPosts.add(pending);
        JSONObject post = (JSONObject) api.post("/posts", body);
        postCache.put(post.optString("id"), post);
        ensureUsers(Collections.singleton(config.userId));
        TLRPC.Message m = toMessage(post, channel, null);
        TLRPC.TL_updates u = new TLRPC.TL_updates();
        TL_update.TL_updateMessageID idu = new TL_update.TL_updateMessageID();
        idu.id = m.id;
        idu.random_id = randomId;
        u.updates.add(idu);
        if (did < 0) {
            TL_update.TL_updateNewChannelMessage n = new TL_update.TL_updateNewChannelMessage();
            n.message = m;
            n.pts = nextChannelPts(-did, 1);
            n.pts_count = 1;
            u.updates.add(n);
            u.chats.add(toChat(channel));
        } else {
            TL_update.TL_updateNewMessage n = new TL_update.TL_updateNewMessage();
            n.message = m;
            n.pts = nextGlobalPts(1);
            n.pts_count = 1;
            u.updates.add(n);
        }
        u.users.add(toUser(users.get(config.userId)));
        if (did > 0) {
            JSONObject other = users.get(dmOther(channel));
            if (other != null) {
                u.users.add(toUser(other));
            }
        }
        u.date = m.date;
        return u;
    }

    private TLObject editPost(TLRPC.TL_messages_editMessage req) throws Exception {
        long did = peerDialogId(req.peer);
        String postId = ids().postAt(did, req.id);
        if (postId == null) {
            throw new MMApi.MMException(400, "Unknown message", null);
        }
        JSONObject body = new JSONObject();
        body.put("message", MMMarkdown.toMarkdown(req.message, req.entities));
        JSONObject post = (JSONObject) api.put("/posts/" + postId + "/patch", body);
        postCache.put(postId, post);
        JSONObject channel = channel(post.optString("channel_id"));
        TLRPC.Message m = toMessage(post, channel, null);
        TLRPC.TL_updates u = new TLRPC.TL_updates();
        u.updates.add(editUpdate(m, did));
        if (did < 0) {
            u.chats.add(toChat(channel));
        }
        u.users.add(toUser(user(config.userId)));
        u.date = now();
        return u;
    }

    private TLRPC.Update editUpdate(TLRPC.Message m, long did) {
        if (did < 0) {
            TL_update.TL_updateEditChannelMessage e = new TL_update.TL_updateEditChannelMessage();
            e.message = m;
            e.pts = nextChannelPts(-did, 1);
            e.pts_count = 1;
            return e;
        } else {
            TL_update.TL_updateEditMessage e = new TL_update.TL_updateEditMessage();
            e.message = m;
            e.pts = nextGlobalPts(1);
            e.pts_count = 1;
            return e;
        }
    }

    private TLObject deletePosts(TLRPC.InputChannel channel, ArrayList<Integer> mids) throws Exception {
        for (Integer mid : mids) {
            String postId = channel != null ? ids().postAt(-channel.channel_id, mid) : ids().postAtUserSpace(mid);
            if (postId != null) {
                try {
                    api.delete("/posts/" + postId);
                } catch (MMApi.MMException e) {
                    if (e.status != 404) {
                        throw e;
                    }
                }
            }
        }
        TLRPC.TL_messages_affectedMessages a = new TLRPC.TL_messages_affectedMessages();
        a.pts_count = mids.size();
        a.pts = channel != null ? nextChannelPts(channel.channel_id, mids.size()) : nextGlobalPts(mids.size());
        return a;
    }

    private void markViewed(String chId) {
        if (chId == null) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("channel_id", chId);
            api.post("/channels/members/me/view", body);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------------------------
    // Users / channels info
    // ------------------------------------------------------------------------------------

    private String mmUserFromInput(TLRPC.InputUser iu) {
        if (iu instanceof TLRPC.TL_inputUserSelf) {
            return config.userId;
        }
        return ids().mm(iu.user_id);
    }

    private TLObject getUsers(ArrayList<TLRPC.InputUser> in) throws Exception {
        HashSet<String> need = new HashSet<>();
        for (TLRPC.InputUser iu : in) {
            String id = mmUserFromInput(iu);
            if (id != null) {
                need.add(id);
            }
        }
        ensureUsers(need);
        loadStatuses(need);
        org.telegram.tgnet.Vector<TLRPC.User> v = new org.telegram.tgnet.Vector<>(TLRPC.User::TLdeserialize);
        for (String id : need) {
            JSONObject u = users.get(id);
            if (u != null) {
                v.objects.add(toUser(u));
            }
        }
        return v;
    }

    private TLObject getFullUser(TLRPC.InputUser iu) throws Exception {
        String id = mmUserFromInput(iu);
        JSONObject u = api.getObject("/users/" + id);
        users.put(id, u);
        loadStatuses(Collections.singleton(id));
        TLRPC.TL_users_userFull r = new TLRPC.TL_users_userFull();
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = ids().userId(id);
        StringBuilder about = new StringBuilder();
        if (!u.optString("position").isEmpty()) {
            about.append(u.optString("position"));
        }
        if (!u.optString("email").isEmpty()) {
            if (about.length() > 0) {
                about.append('\n');
            }
            about.append(u.optString("email"));
        }
        if (about.length() > 0) {
            full.about = about.toString();
            full.flags |= TLObject.FLAG_1;
        }
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        full.user = toUser(u);
        r.full_user = full;
        r.users.add(toUser(u));
        return r;
    }

    private TLObject getFullChannel(TLRPC.InputChannel ic) throws Exception {
        String chId = ids().mm(ic.channel_id);
        JSONObject c = api.getObject("/channels/" + chId);
        putChannel(c);
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = ic.channel_id;
        String about = c.optString("purpose", "");
        String header = c.optString("header", "");
        if (!header.isEmpty()) {
            about = about.isEmpty() ? header : about + "\n\n" + header;
        }
        full.about = about;
        try {
            JSONObject stats = api.getObject("/channels/" + chId + "/stats");
            full.participants_count = stats.optInt("member_count", 0);
            full.flags |= TLObject.FLAG_0;
        } catch (Exception ignore) {
        }
        full.can_view_participants = true;
        full.chat_photo = new TLRPC.TL_photoEmpty();
        JSONObject member = members.get(chId);
        full.notify_settings = notifySettings(member);
        full.pts = channelPts(ic.channel_id);
        full.read_inbox_max_id = 0;
        full.read_outbox_max_id = 0;
        TLRPC.TL_messages_chatFull r = new TLRPC.TL_messages_chatFull();
        r.full_chat = full;
        TLRPC.Chat chat = toChat(c);
        chat.participants_count = full.participants_count;
        r.chats.add(chat);
        return r;
    }

    private TLObject getParticipants(TLRPC.TL_channels_getParticipants req) throws Exception {
        String chId = ids().mm(req.channel.channel_id);
        int limit = req.limit <= 0 ? 100 : Math.min(200, req.limit);
        int page = req.offset / limit;
        JSONArray arr = api.getArray("/users?in_channel=" + chId + "&page=" + page + "&per_page=" + limit + "&sort=status");
        TLRPC.TL_channels_channelParticipants r = new TLRPC.TL_channels_channelParticipants();
        HashSet<String> ids2 = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject u = arr.getJSONObject(i);
            String id = u.optString("id");
            users.put(id, u);
            ids2.add(id);
        }
        loadStatuses(ids2);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject u = arr.getJSONObject(i);
            TLRPC.TL_channelParticipant p = new TLRPC.TL_channelParticipant();
            TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
            pu.user_id = ids().userId(u.optString("id"));
            p.peer = pu;
            p.user_id = pu.user_id;
            p.date = (int) (u.optLong("create_at") / 1000);
            r.participants.add(p);
            r.users.add(toUser(u));
        }
        try {
            JSONObject stats = api.getObject("/channels/" + chId + "/stats");
            r.count = stats.optInt("member_count", r.participants.size());
        } catch (Exception e) {
            r.count = r.participants.size();
        }
        return r;
    }

    private TLObject getContacts() throws Exception {
        TLRPC.TL_contacts_contacts r = new TLRPC.TL_contacts_contacts();
        // contacts = people I have direct messages with
        if (dmByUser.isEmpty()) {
            loadMyChannels();
        }
        HashSet<String> need = new HashSet<>(dmByUser.keySet());
        need.remove(config.userId);
        ensureUsers(need);
        loadStatuses(need);
        for (String id : need) {
            JSONObject u = users.get(id);
            if (u == null || u.optLong("delete_at", 0) > 0) {
                continue;
            }
            TLRPC.TL_contact c = new TLRPC.TL_contact();
            c.user_id = ids().userId(id);
            c.mutual = true;
            r.contacts.add(c);
            r.users.add(toUser(u));
        }
        r.saved_count = 0;
        return r;
    }

    private TLObject searchPeers(String q) throws Exception {
        TLRPC.TL_contacts_found r = new TLRPC.TL_contacts_found();
        JSONObject body = new JSONObject();
        body.put("term", q);
        body.put("limit", 20);
        Object res = api.post("/users/search", body);
        if (res instanceof JSONArray) {
            JSONArray arr = (JSONArray) res;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject u = arr.getJSONObject(i);
                users.put(u.optString("id"), u);
                TLRPC.User tu = toUser(u);
                r.users.add(tu);
                TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
                p.user_id = tu.id;
                r.results.add(p);
            }
        }
        if (config.teamId != null) {
            try {
                JSONObject cb = new JSONObject();
                cb.put("term", q);
                Object cres = api.post("/teams/" + config.teamId + "/channels/search", cb);
                if (cres instanceof JSONArray) {
                    JSONArray arr = (JSONArray) cres;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        putChannel(c);
                        TLRPC.Chat chat = toChat(c);
                        if (!members.containsKey(c.optString("id"))) {
                            chat.left = true;
                        }
                        r.chats.add(chat);
                        TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
                        p.channel_id = chat.id;
                        r.results.add(p);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return r;
    }

    private TLObject resolveUsername(String username) throws Exception {
        JSONObject u = api.getObject("/users/username/" + MMApi.enc(username));
        users.put(u.optString("id"), u);
        TLRPC.TL_contacts_resolvedPeer r = new TLRPC.TL_contacts_resolvedPeer();
        TLRPC.User tu = toUser(u);
        TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
        p.user_id = tu.id;
        r.peer = p;
        r.users.add(tu);
        return r;
    }

    // ------------------------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------------------------

    private TLObject getFile(TLRPC.TL_upload_getFile req) throws Exception {
        TLRPC.InputFileLocation loc = req.location;
        String path;
        if (loc instanceof TLRPC.TL_inputPeerPhotoFileLocation) {
            TLRPC.InputPeer peer = ((TLRPC.TL_inputPeerPhotoFileLocation) loc).peer;
            String uid = peer instanceof TLRPC.TL_inputPeerSelf ? config.userId : ids().mm(peer.user_id);
            if (uid == null) {
                throw new MMApi.MMException(404, "unknown user", null);
            }
            path = "/users/" + uid + "/image";
        } else if (loc instanceof TLRPC.TL_inputPhotoFileLocation || loc instanceof TLRPC.TL_inputDocumentFileLocation) {
            String fid = ids().mm(loc.id);
            if (fid == null) {
                throw new MMApi.MMException(404, "unknown file", null);
            }
            String ts = loc.thumb_size;
            if ("s".equals(ts) || (loc instanceof TLRPC.TL_inputDocumentFileLocation && !TextUtils.isEmpty(ts))) {
                path = "/files/" + fid + "/thumbnail";
            } else if ("x".equals(ts) || "m".equals(ts)) {
                path = "/files/" + fid + "/preview";
            } else {
                path = "/files/" + fid;
            }
        } else {
            throw new MMApi.MMException(400, "unsupported file location " + loc, null);
        }
        byte[] data = api.getBytes(path, req.offset, req.limit);
        TLRPC.TL_upload_file r = new TLRPC.TL_upload_file();
        r.type = new TLRPC.TL_storage_filePartial();
        r.mtime = 0;
        NativeByteBuffer nb = new NativeByteBuffer(Math.max(1, data.length));
        nb.writeBytes(data);
        nb.position(0);
        nb.limit(data.length);
        r.bytes = nb;
        return r;
    }

    // ------------------------------------------------------------------------------------
    // getDifference: catch up on everything since the client's last known date
    // ------------------------------------------------------------------------------------

    private TLObject getDifference(TLRPC.TL_updates_getDifference req) throws Exception {
        long since = Math.max(0, (long) req.date * 1000L - 60_000L);
        TLRPC.TL_updates_difference d = new TLRPC.TL_updates_difference();
        if (req.date > 0) {
            List<JSONObject> chans = loadMyChannels();
            HashSet<String> needUsers = new HashSet<>();
            ArrayList<Object[]> found = new ArrayList<>();
            for (JSONObject c : chans) {
                if (c.optLong("last_post_at", 0) < since && c.optLong("last_root_post_at", 0) < since) {
                    continue;
                }
                JSONObject res = api.getObject("/channels/" + c.optString("id") + "/posts?since=" + since);
                JSONObject posts = res.optJSONObject("posts");
                JSONArray order = res.optJSONArray("order");
                if (posts == null || order == null) {
                    continue;
                }
                ArrayList<JSONObject> list = new ArrayList<>();
                for (int i = 0; i < order.length(); i++) {
                    JSONObject p = posts.optJSONObject(order.getString(i));
                    if (p != null && c.optString("id").equals(p.optString("channel_id"))) {
                        list.add(p);
                    }
                }
                Collections.sort(list, (a, b) -> Long.compare(a.optLong("create_at"), b.optLong("create_at")));
                for (JSONObject p : list) {
                    needUsers.add(p.optString("user_id"));
                    found.add(new Object[]{p, c, posts});
                }
                if (isDm(c)) {
                    needUsers.add(dmOther(c));
                }
            }
            ensureUsers(needUsers);
            HashSet<Long> chatsAdded = new HashSet<>();
            for (Object[] f : found) {
                JSONObject p = (JSONObject) f[0];
                JSONObject c = (JSONObject) f[1];
                long did = dialogId(c);
                boolean known = ids().existingMid(p.optString("id")) != 0;
                if (p.optLong("delete_at", 0) > 0) {
                    if (known) {
                        int mid = ids().existingMid(p.optString("id"));
                        d.other_updates.add(deleteUpdate(did, mid));
                    }
                    continue;
                }
                TLRPC.Message m = toMessage(p, c, (JSONObject) f[2]);
                if (known) {
                    if (p.optLong("edit_at", 0) > since || p.optLong("update_at", 0) > since) {
                        d.other_updates.add(editUpdate(m, did));
                    }
                } else {
                    m.unread = !m.out;
                    d.other_updates.add(newMessageUpdate(m, did));
                }
                if (did < 0 && chatsAdded.add(-did)) {
                    d.chats.add(toChat(c));
                }
            }
            for (String uid : needUsers) {
                JSONObject u = users.get(uid);
                if (u != null) {
                    d.users.add(toUser(u));
                }
            }
        }
        TLRPC.TL_updates_state st = new TLRPC.TL_updates_state();
        st.pts = currentGlobalPts();
        st.date = now();
        st.seq = 0;
        st.qts = 0;
        d.state = st;
        return d;
    }

    private TLRPC.Update newMessageUpdate(TLRPC.Message m, long did) {
        if (did < 0) {
            TL_update.TL_updateNewChannelMessage n = new TL_update.TL_updateNewChannelMessage();
            n.message = m;
            n.pts = nextChannelPts(-did, 1);
            n.pts_count = 1;
            return n;
        } else {
            TL_update.TL_updateNewMessage n = new TL_update.TL_updateNewMessage();
            n.message = m;
            n.pts = nextGlobalPts(1);
            n.pts_count = 1;
            return n;
        }
    }

    private TLRPC.Update deleteUpdate(long did, int mid) {
        if (did < 0) {
            TL_update.TL_updateDeleteChannelMessages u = new TL_update.TL_updateDeleteChannelMessages();
            u.channel_id = -did;
            u.messages.add(mid);
            u.pts = nextChannelPts(-did, 1);
            u.pts_count = 1;
            return u;
        } else {
            TL_update.TL_updateDeleteMessages u = new TL_update.TL_updateDeleteMessages();
            u.messages.add(mid);
            u.pts = nextGlobalPts(1);
            u.pts_count = 1;
            return u;
        }
    }

    // ------------------------------------------------------------------------------------
    // Websocket (live events)
    // ------------------------------------------------------------------------------------

    public synchronized void startWebSocket() {
        if (api == null) {
            return;
        }
        wsWanted = true;
        if (ws != null && ws.isOpen()) {
            return;
        }
        connectWs();
    }

    public synchronized void stopWebSocket() {
        wsWanted = false;
        if (ws != null) {
            ws.disconnect();
            ws = null;
        }
    }

    private void setState(int state) {
        ConnectionsManager.onConnectionStateChanged(state, account);
    }

    private synchronized void connectWs() {
        try {
            setState(ConnectionsManager.ConnectionStateConnecting);
            String url = api.getServer().replaceFirst("^http", "ws") + "/api/v4/websocket";
            WebSocket socket = new WebSocketFactory().setConnectionTimeout(15000).createSocket(url);
            socket.addHeader("Authorization", "Bearer " + api.getToken());
            socket.setPingInterval(30_000);
            socket.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    wsBackoff = 1;
                    setState(ConnectionsManager.ConnectionStateConnected);
                    // catch up on anything missed while disconnected
                    Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(account).getMessagesController().getDifference());
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    pool.execute(() -> {
                        try {
                            onWsEvent(new JSONObject(text));
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    });
                }

                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    scheduleReconnect();
                }

                @Override
                public void onConnectError(WebSocket websocket, com.neovisionaries.ws.client.WebSocketException exception) {
                    FileLog.e("mm ws connect error " + exception);
                    scheduleReconnect();
                }
            });
            ws = socket;
            socket.connectAsynchronously();
        } catch (Exception e) {
            FileLog.e(e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!wsWanted) {
            return;
        }
        setState(ApplicationLoader.isNetworkOnline() ? ConnectionsManager.ConnectionStateConnecting : ConnectionsManager.ConnectionStateWaitingForNetwork);
        int delay = Math.min(60, wsBackoff);
        wsBackoff = Math.min(60, wsBackoff * 2);
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (MMBackend.this) {
                if (wsWanted && (ws == null || !ws.isOpen())) {
                    connectWs();
                }
            }
        }, delay * 1000L);
    }

    /**
     * Called when the app comes to foreground / network returns.
     */
    public void ensureConnected() {
        synchronized (this) {
            if (api != null && (ws == null || !ws.isOpen())) {
                wsWanted = true;
                wsBackoff = 1;
                connectWs();
            }
        }
    }

    private void sendTyping(String channelId) {
        try {
            WebSocket s = ws;
            if (s == null || !s.isOpen()) {
                return;
            }
            JSONObject msg = new JSONObject();
            msg.put("action", "user_typing");
            msg.put("seq", wsSeq.incrementAndGet());
            JSONObject data = new JSONObject();
            data.put("channel_id", channelId);
            data.put("parent_id", "");
            msg.put("data", data);
            s.sendText(msg.toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void pushUpdates(TLRPC.TL_updates u) {
        if (u.updates.isEmpty()) {
            return;
        }
        u.date = now();
        Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(account).getMessagesController().processUpdates(u, false));
    }

    private void onWsEvent(JSONObject ev) throws Exception {
        String event = ev.optString("event");
        JSONObject data = ev.optJSONObject("data");
        JSONObject broadcast = ev.optJSONObject("broadcast");
        if (event.isEmpty() || data == null) {
            return;
        }
        switch (event) {
            case "posted": {
                JSONObject post = new JSONObject(data.getString("post"));
                String pending = post.optString("pending_post_id", "");
                if (!pending.isEmpty() && ownPendingPosts.remove(pending)) {
                    return; // already delivered as the response to sendMessage
                }
                if (ids().existingMid(post.optString("id")) != 0) {
                    return;
                }
                JSONObject channel = channel(post.optString("channel_id"));
                if (channel == null) {
                    return;
                }
                channel.put("last_post_at", post.optLong("create_at"));
                HashSet<String> need = new HashSet<>();
                need.add(post.optString("user_id"));
                if (isDm(channel)) {
                    need.add(dmOther(channel));
                }
                ensureUsers(need);
                long did = dialogId(channel);
                TLRPC.Message m = toMessage(post, channel, null);
                m.unread = !m.out;
                if (!m.out && data.has("mentions")) {
                    String mentions = data.optString("mentions", "");
                    if (mentions.contains(config.userId)) {
                        m.mentioned = true;
                        m.media_unread = true;
                    }
                }
                TLRPC.TL_updates u = new TLRPC.TL_updates();
                u.updates.add(newMessageUpdate(m, did));
                for (String uid : need) {
                    u.users.add(toUser(users.get(uid)));
                }
                if (did < 0) {
                    u.chats.add(toChat(channel));
                }
                pushUpdates(u);
                break;
            }
            case "post_edited": {
                JSONObject post = new JSONObject(data.getString("post"));
                postCache.put(post.optString("id"), post);
                if (ids().existingMid(post.optString("id")) == 0) {
                    return;
                }
                JSONObject channel = channel(post.optString("channel_id"));
                ensureUsers(Collections.singleton(post.optString("user_id")));
                long did = dialogId(channel);
                TLRPC.Message m = toMessage(post, channel, null);
                TLRPC.TL_updates u = new TLRPC.TL_updates();
                u.updates.add(editUpdate(m, did));
                u.users.add(toUser(users.get(post.optString("user_id"))));
                if (did < 0) {
                    u.chats.add(toChat(channel));
                }
                pushUpdates(u);
                break;
            }
            case "post_deleted": {
                JSONObject post = new JSONObject(data.getString("post"));
                int mid = ids().existingMid(post.optString("id"));
                if (mid == 0) {
                    return;
                }
                JSONObject channel = channel(post.optString("channel_id"));
                long did = dialogId(channel);
                TLRPC.TL_updates u = new TLRPC.TL_updates();
                u.updates.add(deleteUpdate(did, mid));
                pushUpdates(u);
                break;
            }
            case "typing": {
                String uid = data.optString("user_id");
                String chId = broadcast != null ? broadcast.optString("channel_id") : null;
                if (uid.equals(config.userId) || chId == null) {
                    return;
                }
                JSONObject channel = channel(chId);
                ensureUsers(Collections.singleton(uid));
                long did = dialogId(channel);
                TLRPC.TL_updates u = new TLRPC.TL_updates();
                if (did > 0) {
                    TL_update.TL_updateUserTyping t = new TL_update.TL_updateUserTyping();
                    t.user_id = ids().userId(uid);
                    t.action = new TLRPC.TL_sendMessageTypingAction();
                    u.updates.add(t);
                } else {
                    TL_update.TL_updateChannelUserTyping t = new TL_update.TL_updateChannelUserTyping();
                    t.channel_id = -did;
                    TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
                    from.user_id = ids().userId(uid);
                    t.from_id = from;
                    t.action = new TLRPC.TL_sendMessageTypingAction();
                    u.updates.add(t);
                    u.chats.add(toChat(channel));
                }
                u.users.add(toUser(users.get(uid)));
                pushUpdates(u);
                break;
            }
            case "channel_viewed":
            case "multiple_channels_viewed": {
                ArrayList<String> chIds = new ArrayList<>();
                if (data.has("channel_id")) {
                    chIds.add(data.optString("channel_id"));
                }
                JSONObject times = data.optJSONObject("channel_times");
                if (times != null) {
                    JSONArray names = times.names();
                    for (int i = 0; names != null && i < names.length(); i++) {
                        chIds.add(names.getString(i));
                    }
                }
                TLRPC.TL_updates u = new TLRPC.TL_updates();
                for (String chId : chIds) {
                    JSONObject channel = channel(chId);
                    if (channel == null) {
                        continue;
                    }
                    long did = dialogId(channel);
                    int maxId = MMIds.timeToMid(System.currentTimeMillis()) + 1000;
                    if (did < 0) {
                        TL_update.TL_updateReadChannelInbox r = new TL_update.TL_updateReadChannelInbox();
                        r.channel_id = -did;
                        r.max_id = maxId;
                        r.still_unread_count = 0;
                        r.pts = channelPts(-did);
                        u.updates.add(r);
                        u.chats.add(toChat(channel));
                    } else {
                        TL_update.TL_updateReadHistoryInbox r = new TL_update.TL_updateReadHistoryInbox();
                        r.peer = peerFor(did);
                        r.max_id = maxId;
                        r.still_unread_count = 0;
                        r.pts = nextGlobalPts(1);
                        r.pts_count = 1;
                        u.updates.add(r);
                    }
                }
                pushUpdates(u);
                break;
            }
            case "status_change": {
                String uid = data.optString("user_id");
                statuses.put(uid, data.optString("status"));
                JSONObject uj = users.get(uid);
                if (uj == null) {
                    return;
                }
                TLRPC.User tu = toUser(uj);
                TL_update.TL_updateUserStatus s = new TL_update.TL_updateUserStatus();
                s.user_id = tu.id;
                s.status = tu.status;
                TLRPC.TL_updates u = new TLRPC.TL_updates();
                u.updates.add(s);
                u.users.add(tu);
                pushUpdates(u);
                break;
            }
            case "channel_updated":
            case "channel_created":
            case "direct_added":
            case "group_added":
            case "user_added": {
                String chId = data.optString("channel_id", broadcast != null ? broadcast.optString("channel_id") : "");
                if (!chId.isEmpty()) {
                    channels.remove(chId);
                }
                break;
            }
            default:
                break;
        }
    }
}
