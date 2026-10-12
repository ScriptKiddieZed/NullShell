package com.zed.nullshell;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Local storage for assistants, conversations and messages (a private SQLite database). */
final class ChatStore extends SQLiteOpenHelper {
    private static ChatStore instance;

    static synchronized ChatStore get(Context c) {
        if (instance == null) instance = new ChatStore(c.getApplicationContext());
        return instance;
    }

    private final Context appContext;

    private ChatStore(Context c) { super(c, "chat.db", null, 2); appContext = c; }

    static File filesDir(Context c) {
        File d = new File(c.getFilesDir(), "ai_files");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static final class Assistant {
        long id;
        String name = "", emoji = "✨", prompt = "", model = "";   // model "" = use the default
        float temp = -1;       // -1 = API default
        int maxTokens = 0;     // 0 = API default
        String label() { return (emoji.isEmpty() ? "" : emoji + " ") + name; }
    }

    static final class Conversation {
        long id, assistantId, updated;
        String title = "";
    }

    static final class Attachment {
        long id, msgId, size;
        String name = "", mime = "", path = "";
        File file() { return new File(path); }
        boolean isImage() { return AttachKind.isImage(mime); }
        boolean isText() { return AttachKind.TEXT.equals(mime); }
    }

    static final class Message {
        long id, convId, ts;
        String role = "user", text = "";
        boolean isUser() { return "user".equals(role); }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE assistants(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, emoji TEXT, prompt TEXT, model TEXT, temp REAL, max_tokens INTEGER, sort INTEGER)");
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, assistant_id INTEGER, title TEXT, updated INTEGER)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER, role TEXT, text TEXT, ts INTEGER)");
        db.execSQL("CREATE INDEX msg_conv ON messages(conv_id)");
        createAttachments(db);
        seed(db, "General", "💬", "You are a helpful, accurate assistant. Be concise; use Markdown, and put code in fenced code blocks with the language name.");
        seed(db, "Kali helper", "🐉",
                "You help with authorised security testing, CTFs and learning, on Kali Linux running inside an Android PRoot (no root, no raw sockets or netlink, so tools such as nmap need unprivileged options). "
                + "Give exact, copy-pasteable commands in fenced code blocks, explain flags briefly, and say when something will not work in this environment. "
                + "Remind the user to test only systems they own or have written permission to test.");
        seed(db, "Code reviewer", "🔍",
                "You are a careful senior code reviewer. Look for bugs, edge cases, security problems and unclear code. List findings by severity, quote the relevant lines, and suggest concrete fixes as code blocks. Do not praise filler; be direct.");
        seed(db, "Shell explainer", "🐚",
                "Explain shell commands and scripts the user pastes: what each part does, what could go wrong, and a safer variant if the command is risky. Keep it short and use a code block for any corrected command.");
    }

    private void seed(SQLiteDatabase db, String name, String emoji, String prompt) {
        ContentValues v = new ContentValues();
        v.put("name", name); v.put("emoji", emoji); v.put("prompt", prompt); v.put("model", "");
        v.put("temp", -1.0); v.put("max_tokens", 0);
        v.put("sort", countRows(db, "assistants"));
        db.insert("assistants", null, v);
    }

    private static int countRows(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    private static void createAttachments(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS attachments(id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, name TEXT, mime TEXT, size INTEGER, path TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS att_msg ON attachments(msg_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) createAttachments(db);
    }

    // ------------------------------------------------------------------ assistants

    List<Assistant> assistants() {
        List<Assistant> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,emoji,prompt,model,temp,max_tokens FROM assistants ORDER BY sort,id", null)) {
            while (c.moveToNext()) out.add(readAssistant(c));
        }
        return out;
    }

    Assistant assistant(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,emoji,prompt,model,temp,max_tokens FROM assistants WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readAssistant(c) : null;
        }
    }

    private static Assistant readAssistant(Cursor c) {
        Assistant a = new Assistant();
        a.id = c.getLong(0); a.name = c.getString(1); a.emoji = c.getString(2); a.prompt = c.getString(3);
        a.model = c.getString(4) == null ? "" : c.getString(4);
        a.temp = c.getFloat(5); a.maxTokens = c.getInt(6);
        return a;
    }

    long saveAssistant(Assistant a) {
        ContentValues v = new ContentValues();
        v.put("name", a.name); v.put("emoji", a.emoji); v.put("prompt", a.prompt); v.put("model", a.model);
        v.put("temp", a.temp); v.put("max_tokens", a.maxTokens);
        SQLiteDatabase db = getWritableDatabase();
        if (a.id == 0) {
            v.put("sort", countRows(db, "assistants") + 1000);
            a.id = db.insert("assistants", null, v);
        } else {
            db.update("assistants", v, "id=?", new String[]{String.valueOf(a.id)});
        }
        return a.id;
    }

    void deleteAssistant(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            purgeAttachments(db, "msg_id IN (SELECT id FROM messages WHERE conv_id IN (SELECT id FROM conversations WHERE assistant_id=?))", new String[]{String.valueOf(id)});
            db.execSQL("DELETE FROM messages WHERE conv_id IN (SELECT id FROM conversations WHERE assistant_id=?)", new Object[]{id});
            db.execSQL("DELETE FROM conversations WHERE assistant_id=?", new Object[]{id});
            db.execSQL("DELETE FROM assistants WHERE id=?", new Object[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    // ------------------------------------------------------------------ conversations

    long newConversation(long assistantId) {
        ContentValues v = new ContentValues();
        v.put("assistant_id", assistantId); v.put("title", ""); v.put("updated", System.currentTimeMillis());
        return getWritableDatabase().insert("conversations", null, v);
    }

    List<Conversation> conversations(long assistantId) {
        List<Conversation> out = new ArrayList<>();
        String sql = "SELECT id,assistant_id,title,updated FROM conversations "
                + (assistantId > 0 ? "WHERE assistant_id=? " : "") + "ORDER BY updated DESC LIMIT 300";
        try (Cursor c = getReadableDatabase().rawQuery(sql, assistantId > 0 ? new String[]{String.valueOf(assistantId)} : null)) {
            while (c.moveToNext()) out.add(readConv(c));
        }
        return out;
    }

    Conversation conversation(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,assistant_id,title,updated FROM conversations WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readConv(c) : null;
        }
    }

    /** Most recently used conversation of an assistant that has no messages yet, so "New chat" doesn't pile up empty ones. */
    long emptyConversation(long assistantId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT c.id FROM conversations c WHERE c.assistant_id=? AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.conv_id=c.id) ORDER BY c.updated DESC LIMIT 1",
                new String[]{String.valueOf(assistantId)})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static Conversation readConv(Cursor c) {
        Conversation v = new Conversation();
        v.id = c.getLong(0); v.assistantId = c.getLong(1); v.title = c.getString(2) == null ? "" : c.getString(2); v.updated = c.getLong(3);
        return v;
    }

    void renameConversation(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("conversations", v, "id=?", new String[]{String.valueOf(id)});
    }

    void touch(long id) {
        ContentValues v = new ContentValues();
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("conversations", v, "id=?", new String[]{String.valueOf(id)});
    }

    void deleteConversation(long id) {
        SQLiteDatabase db = getWritableDatabase();
        purgeAttachments(db, "msg_id IN (SELECT id FROM messages WHERE conv_id=?)", new String[]{String.valueOf(id)});
        db.delete("messages", "conv_id=?", new String[]{String.valueOf(id)});
        db.delete("conversations", "id=?", new String[]{String.valueOf(id)});
    }

    void clearMessages(long convId) {
        SQLiteDatabase db = getWritableDatabase();
        purgeAttachments(db, "msg_id IN (SELECT id FROM messages WHERE conv_id=?)", new String[]{String.valueOf(convId)});
        db.delete("messages", "conv_id=?", new String[]{String.valueOf(convId)});
    }

    void deleteEverything() {
        SQLiteDatabase db = getWritableDatabase();
        purgeAttachments(db, "1=1", null);
        db.delete("messages", null, null);
        db.delete("conversations", null, null);
    }

    // ------------------------------------------------------------------ messages

    List<Message> messages(long convId) {
        List<Message> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,conv_id,role,text,ts FROM messages WHERE conv_id=? ORDER BY id", new String[]{String.valueOf(convId)})) {
            while (c.moveToNext()) {
                Message m = new Message();
                m.id = c.getLong(0); m.convId = c.getLong(1); m.role = c.getString(2); m.text = c.getString(3); m.ts = c.getLong(4);
                out.add(m);
            }
        }
        return out;
    }

    Message addMessage(long convId, String role, String text) {
        Message m = new Message();
        m.convId = convId; m.role = role; m.text = text; m.ts = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("conv_id", convId); v.put("role", role); v.put("text", text); v.put("ts", m.ts);
        m.id = getWritableDatabase().insert("messages", null, v);
        touch(convId);
        return m;
    }

    void deleteMessage(long id) {
        SQLiteDatabase db = getWritableDatabase();
        purgeAttachments(db, "msg_id=?", new String[]{String.valueOf(id)});
        db.delete("messages", "id=?", new String[]{String.valueOf(id)});
    }

    // ------------------------------------------------------------------ attachments

    long addAttachment(long msgId, String name, String mime, long size, File stored) {
        ContentValues v = new ContentValues();
        v.put("msg_id", msgId); v.put("name", name); v.put("mime", mime); v.put("size", size); v.put("path", stored.getPath());
        return getWritableDatabase().insert("attachments", null, v);
    }

    /** All attachments of a conversation, by message id. */
    Map<Long, List<Attachment>> attachments(long convId) {
        Map<Long, List<Attachment>> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT a.id,a.msg_id,a.name,a.mime,a.size,a.path FROM attachments a JOIN messages m ON m.id=a.msg_id WHERE m.conv_id=? ORDER BY a.id",
                new String[]{String.valueOf(convId)})) {
            while (c.moveToNext()) {
                Attachment a = new Attachment();
                a.id = c.getLong(0); a.msgId = c.getLong(1); a.name = c.getString(2); a.mime = c.getString(3); a.size = c.getLong(4); a.path = c.getString(5);
                List<Attachment> l = out.get(a.msgId);
                if (l == null) { l = new ArrayList<>(); out.put(a.msgId, l); }
                l.add(a);
            }
        }
        return out;
    }

    private void purgeAttachments(SQLiteDatabase db, String where, String[] args) {
        try (Cursor c = db.rawQuery("SELECT path FROM attachments WHERE " + where, args)) {
            while (c.moveToNext()) { String p = c.getString(0); if (p != null) new File(p).delete(); }
        }
        db.delete("attachments", where, args);
    }
}
