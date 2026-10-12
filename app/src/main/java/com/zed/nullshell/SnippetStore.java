package com.zed.nullshell;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists snippets as one JSON file in app storage (outside the rootfs, so "Delete rootfs"
 * never touches them). Main-thread use; every change is written immediately.
 */
public final class SnippetStore {
    private static final SnippetStore INSTANCE = new SnippetStore();
    public static SnippetStore get() { return INSTANCE; }

    private final List<Snippet> items = new ArrayList<>();
    private File file;
    private boolean loaded;

    private SnippetStore() { }

    public synchronized void init(Context c) {
        if (loaded) return;
        file = new File(c.getFilesDir(), "snippets.json");
        loaded = true;
        if (!file.isFile()) return;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            items.addAll(parse(json));
        } catch (Exception ignored) { }
    }

    /** Sorted by group, then manual order, then name. */
    public synchronized List<Snippet> all() {
        List<Snippet> copy = new ArrayList<>(items);
        Collections.sort(copy, (a, b) -> {
            int g = a.group.compareToIgnoreCase(b.group);
            if (g != 0) return g;
            if (a.order != b.order) return Integer.compare(a.order, b.order);
            return a.name.compareToIgnoreCase(b.name);
        });
        return copy;
    }

    public synchronized List<Snippet> pinned() {
        List<Snippet> out = new ArrayList<>();
        for (Snippet s : all()) if (s.pinned) out.add(s);
        return out;
    }

    public synchronized Snippet find(String id) {
        for (Snippet s : items) if (s.id.equals(id)) return s;
        return null;
    }

    public synchronized List<String> groups() {
        List<String> g = new ArrayList<>();
        for (Snippet s : all()) if (!s.group.isEmpty() && !g.contains(s.group)) g.add(s.group);
        return g;
    }

    public synchronized void put(Snippet s) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(s.id)) { items.set(i, s); save(); return; }
        s.order = items.size();
        items.add(s);
        save();
    }

    public synchronized void delete(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) { items.remove(i); break; }
        save();
    }

    public synchronized Snippet duplicate(String id) {
        Snippet s = find(id);
        if (s == null) return null;
        Snippet c = s.copy();
        c.id = java.util.UUID.randomUUID().toString();
        c.name = s.name + " copy";
        c.pinned = false;
        put(c);
        return c;
    }

    /** Remembers the last form values without touching anything else. */
    public synchronized void remember(Snippet s) { put(s); }

    // ------------------------------------------------------------------ import / export

    public synchronized String exportJson() {
        try { return toJson(items).toString(2); } catch (JSONException e) { return "{}"; }
    }

    /** Adds the snippets from an exported file; ids that already exist are replaced. Returns how many were read. */
    public synchronized int importJson(String json) throws JSONException {
        List<Snippet> in = parse(json);
        for (Snippet s : in) {
            boolean replaced = false;
            for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(s.id)) { items.set(i, s); replaced = true; break; }
            if (!replaced) items.add(s);
        }
        save();
        return in.size();
    }

    private static JSONObject toJson(List<Snippet> list) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONArray a = new JSONArray();
        for (Snippet s : list) a.put(s.toJson());
        root.put("snippets", a);
        return root;
    }

    private static List<Snippet> parse(String json) throws JSONException {
        List<Snippet> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray a = root.optJSONArray("snippets");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(Snippet.fromJson(o));
        }
        return out;
    }

    private void save() {
        if (file == null) return;
        try {
            File tmp = new File(file.getParentFile(), "snippets.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(toJson(items).toString(2).getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | JSONException ignored) { }
    }
}
