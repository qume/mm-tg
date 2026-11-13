package org.telegram.mattermost;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;

/**
 * Maps Mattermost string ids to the numeric ids the Telegram UI layer needs.
 * <p>
 * Users, channels, files: deterministic 40-bit hash of the Mattermost id (+ reverse table).
 * Posts: per-dialog 32-bit message id derived from the post's create_at (quarter seconds
 * since 2015-01-01), so ids are monotonic in time; collisions are resolved by probing.
 */
public class MMIds {

    public static final long EPOCH_MS = 1420070400000L; // 2015-01-01
    public static final int KIND_USER = 1;
    public static final int KIND_CHANNEL = 2;
    public static final int KIND_FILE = 3;
    public static final int KIND_EMOJI = 4;

    private final SQLiteDatabase db;
    private final HashMap<Long, String> reverse = new HashMap<>();
    private final HashMap<String, Integer> postToMid = new HashMap<>();
    private final HashMap<String, String> midToPost = new HashMap<>();

    public MMIds(int account) {
        SQLiteOpenHelper helper = new SQLiteOpenHelper(ApplicationLoader.applicationContext, "mmtg_ids_" + account + ".db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE ids (nid INTEGER PRIMARY KEY, mmid TEXT, kind INTEGER)");
                db.execSQL("CREATE TABLE posts (did INTEGER, mid INTEGER, post TEXT, PRIMARY KEY (did, mid))");
                db.execSQL("CREATE INDEX posts_post ON posts(post)");
                db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };
        db = helper.getWritableDatabase();
    }

    public void clearAll() {
        synchronized (this) {
            db.execSQL("DELETE FROM ids");
            db.execSQL("DELETE FROM posts");
            db.execSQL("DELETE FROM kv");
            reverse.clear();
            postToMid.clear();
            midToPost.clear();
        }
    }

    private static long hash40(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 5; i++) {
                v = (v << 8) | (d[i] & 0xff);
            }
            return v;
        } catch (Exception e) {
            return s.hashCode() & 0xffffffffL;
        }
    }

    public long id(int kind, String mmId) {
        if (mmId == null) {
            return 0;
        }
        // keep well below any special-meaning bits used by Telegram dialog ids
        long base = (1L << 40) + hash40(kind + ":" + mmId);
        synchronized (this) {
            String existing = reverse.get(base);
            if (mmId.equals(existing)) {
                return base;
            }
            long nid = base;
            while (true) {
                String cur = reverse.get(nid);
                if (cur == null) {
                    cur = lookup(nid);
                }
                if (cur == null) {
                    ContentValues cv = new ContentValues();
                    cv.put("nid", nid);
                    cv.put("mmid", mmId);
                    cv.put("kind", kind);
                    db.insertWithOnConflict("ids", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    reverse.put(nid, mmId);
                    return nid;
                }
                reverse.put(nid, cur);
                if (cur.equals(mmId)) {
                    return nid;
                }
                nid++;
            }
        }
    }

    private String lookup(long nid) {
        Cursor c = db.rawQuery("SELECT mmid FROM ids WHERE nid = ?", new String[]{String.valueOf(nid)});
        try {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
            return null;
        } finally {
            c.close();
        }
    }

    public String mm(long nid) {
        synchronized (this) {
            String s = reverse.get(nid);
            if (s == null) {
                s = lookup(nid);
                if (s != null) {
                    reverse.put(nid, s);
                }
            }
            return s;
        }
    }

    public long userId(String mmId) {
        return id(KIND_USER, mmId);
    }

    public long chatId(String mmId) {
        return id(KIND_CHANNEL, mmId);
    }

    public long fileId(String mmId) {
        return id(KIND_FILE, mmId);
    }

    public static int timeToMid(long createAtMs) {
        long v = (createAtMs - EPOCH_MS) / 250L;
        if (v < 1) {
            v = 1;
        }
        if (v > Integer.MAX_VALUE - 1000) {
            v = Integer.MAX_VALUE - 1000;
        }
        return (int) v;
    }

    public static long midToTime(int mid) {
        return EPOCH_MS + mid * 250L;
    }

    private static String key(long did, int mid) {
        return did + "_" + mid;
    }

    /**
     * Returns the message id for a post in a dialog, allocating one if needed.
     */
    public int mid(long did, String postId, long createAt) {
        synchronized (this) {
            Integer cached = postToMid.get(postId);
            if (cached != null) {
                return cached;
            }
            Cursor c = db.rawQuery("SELECT mid FROM posts WHERE post = ?", new String[]{postId});
            try {
                if (c.moveToFirst()) {
                    int m = c.getInt(0);
                    postToMid.put(postId, m);
                    midToPost.put(key(did, m), postId);
                    return m;
                }
            } finally {
                c.close();
            }
            int m = timeToMid(createAt);
            // private chats share one message id space in Telegram, so keep them globally unique
            while (did > 0 ? postAtUserSpace(m) != null : postAt(did, m) != null) {
                m++;
            }
            ContentValues cv = new ContentValues();
            cv.put("did", did);
            cv.put("mid", m);
            cv.put("post", postId);
            db.insertWithOnConflict("posts", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            postToMid.put(postId, m);
            midToPost.put(key(did, m), postId);
            return m;
        }
    }

    /**
     * Existing message id for a post, or 0 if never seen.
     */
    public int existingMid(String postId) {
        synchronized (this) {
            Integer cached = postToMid.get(postId);
            if (cached != null) {
                return cached;
            }
            Cursor c = db.rawQuery("SELECT mid FROM posts WHERE post = ?", new String[]{postId});
            try {
                if (c.moveToFirst()) {
                    int m = c.getInt(0);
                    postToMid.put(postId, m);
                    return m;
                }
                return 0;
            } finally {
                c.close();
            }
        }
    }

    public String postAt(long did, int mid) {
        synchronized (this) {
            String k = key(did, mid);
            String p = midToPost.get(k);
            if (p != null) {
                return p;
            }
            Cursor c = db.rawQuery("SELECT post FROM posts WHERE did = ? AND mid = ?", new String[]{String.valueOf(did), String.valueOf(mid)});
            try {
                if (c.moveToFirst()) {
                    p = c.getString(0);
                    midToPost.put(k, p);
                    return p;
                }
                return null;
            } finally {
                c.close();
            }
        }
    }

    public String postAtUserSpace(int mid) {
        synchronized (this) {
            Cursor c = db.rawQuery("SELECT post FROM posts WHERE did > 0 AND mid = ? LIMIT 1", new String[]{String.valueOf(mid)});
            try {
                return c.moveToFirst() ? c.getString(0) : null;
            } finally {
                c.close();
            }
        }
    }

    /**
     * Find the post with the greatest mid <= given mid in a dialog (for read markers / offsets).
     */
    public String postAtOrBefore(long did, int mid) {
        synchronized (this) {
            Cursor c = db.rawQuery("SELECT post FROM posts WHERE did = ? AND mid <= ? ORDER BY mid DESC LIMIT 1", new String[]{String.valueOf(did), String.valueOf(mid)});
            try {
                return c.moveToFirst() ? c.getString(0) : null;
            } finally {
                c.close();
            }
        }
    }

    public String getKv(String k) {
        synchronized (this) {
            Cursor c = db.rawQuery("SELECT v FROM kv WHERE k = ?", new String[]{k});
            try {
                return c.moveToFirst() ? c.getString(0) : null;
            } finally {
                c.close();
            }
        }
    }

    public void putKv(String k, String v) {
        synchronized (this) {
            ContentValues cv = new ContentValues();
            cv.put("k", k);
            cv.put("v", v);
            db.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
}
