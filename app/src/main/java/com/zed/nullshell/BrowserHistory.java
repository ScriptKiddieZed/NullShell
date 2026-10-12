package com.zed.nullshell;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visited pages: one row per URL with last visit time and visit count. Writes run off the UI thread. */
final class BrowserHistory extends SQLiteOpenHelper {
    static final class Item {
        final long id, last;
        final int visits;
        final String url, title;
        Item(long id, String url, String title, long last, int visits) {
            this.id = id; this.url = url; this.title = title == null ? "" : title; this.last = last; this.visits = visits;
        }
    }

    private static final int MAX_ROWS = 5000;
    private static BrowserHistory instance;

    static synchronized BrowserHistory get(Context c) {
        if (instance == null) instance = new BrowserHistory(c.getApplicationContext());
        return instance;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "browser-history");
        t.setDaemon(true);
        return t;
    });

    private BrowserHistory(Context c) { super(c, "browser_history.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, "
                + "title TEXT, last INTEGER NOT NULL, visits INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE INDEX history_last ON history(last)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    static boolean recordable(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    void visit(final String url, final String title) {
        if (!recordable(url)) return;
        io.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                long now = System.currentTimeMillis();
                SQLiteStatement up = db.compileStatement(
                        "UPDATE history SET last=?, visits=visits+1, title=CASE WHEN ?<>'' THEN ? ELSE title END WHERE url=?");
                String t = title == null ? "" : title;
                up.bindLong(1, now);
                up.bindString(2, t);
                up.bindString(3, t);
                up.bindString(4, url);
                int rows = up.executeUpdateDelete();
                up.close();
                if (rows == 0) {
                    SQLiteStatement ins = db.compileStatement("INSERT INTO history(url, title, last, visits) VALUES(?,?,?,1)");
                    ins.bindString(1, url);
                    ins.bindString(2, t);
                    ins.bindLong(3, now);
                    ins.executeInsert();
                    ins.close();
                    db.execSQL("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY last DESC LIMIT -1 OFFSET " + MAX_ROWS + ")");
                }
            } catch (Exception ignored) { }
        });
    }

    void setTitle(final String url, final String title) {
        if (!recordable(url) || title == null || title.isEmpty()) return;
        io.execute(() -> {
            try { getWritableDatabase().execSQL("UPDATE history SET title=? WHERE url=?", new Object[]{title, url}); }
            catch (Exception ignored) { }
        });
    }

    /** Newest first. An empty query lists everything. */
    List<Item> search(String query, int limit) {
        List<Item> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (Cursor c = q.isEmpty()
                ? getReadableDatabase().rawQuery("SELECT id,url,title,last,visits FROM history ORDER BY last DESC LIMIT " + limit, null)
                : getReadableDatabase().rawQuery("SELECT id,url,title,last,visits FROM history WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' "
                        + "ORDER BY last DESC LIMIT " + limit, new String[]{like, like})) {
            while (c.moveToNext()) out.add(new Item(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)));
        } catch (Exception ignored) { }
        return out;
    }

    void delete(final long id) {
        io.execute(() -> {
            try { getWritableDatabase().execSQL("DELETE FROM history WHERE id=?", new Object[]{id}); } catch (Exception ignored) { }
        });
    }

    void clear() {
        io.execute(() -> {
            try { getWritableDatabase().execSQL("DELETE FROM history"); } catch (Exception ignored) { }
        });
    }
}
