package com.zed.nullshell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A saved command / template / macro. The body is parsed by {@link SnippetTemplate}. */
public final class Snippet {
    public static final class Preset {
        public String name = "";
        public Map<String, String> values = new LinkedHashMap<>();
        public Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
    }

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String group = "";
    public String body = "";
    public boolean enter = true;      // press Enter after the text
    public boolean confirm;           // ask before running
    public boolean pinned;            // show as a chip on the extra-keys row
    public int order;

    public Map<String, String> lastValues = new LinkedHashMap<>();
    public Map<String, List<Map<String, String>>> lastRows = new LinkedHashMap<>();
    public List<Preset> presets = new ArrayList<>();

    public SnippetTemplate template() { return SnippetTemplate.parse(body); }

    public Snippet copy() {
        try { return fromJson(toJson()); } catch (JSONException e) { return this; }
    }

    // ------------------------------------------------------------------ JSON

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("group", group);
        o.put("body", body);
        o.put("enter", enter);
        o.put("confirm", confirm);
        o.put("pinned", pinned);
        o.put("order", order);
        o.put("lastValues", mapToJson(lastValues));
        o.put("lastRows", rowsToJson(lastRows));
        JSONArray ps = new JSONArray();
        for (Preset p : presets) {
            JSONObject po = new JSONObject();
            po.put("name", p.name);
            po.put("values", mapToJson(p.values));
            po.put("rows", rowsToJson(p.rows));
            ps.put(po);
        }
        o.put("presets", ps);
        return o;
    }

    public static Snippet fromJson(JSONObject o) throws JSONException {
        Snippet s = new Snippet();
        s.id = o.optString("id", s.id);
        s.name = o.optString("name", "");
        s.group = o.optString("group", "");
        s.body = o.optString("body", "");
        s.enter = o.optBoolean("enter", true);
        s.confirm = o.optBoolean("confirm", false);
        s.pinned = o.optBoolean("pinned", false);
        s.order = o.optInt("order", 0);
        s.lastValues = jsonToMap(o.optJSONObject("lastValues"));
        s.lastRows = jsonToRows(o.optJSONObject("lastRows"));
        JSONArray ps = o.optJSONArray("presets");
        if (ps != null) for (int i = 0; i < ps.length(); i++) {
            JSONObject po = ps.optJSONObject(i);
            if (po == null) continue;
            Preset p = new Preset();
            p.name = po.optString("name", "");
            p.values = jsonToMap(po.optJSONObject("values"));
            p.rows = jsonToRows(po.optJSONObject("rows"));
            s.presets.add(p);
        }
        return s;
    }

    private static JSONObject mapToJson(Map<String, String> m) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        return o;
    }

    private static JSONObject rowsToJson(Map<String, List<Map<String, String>>> rows) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, List<Map<String, String>>> e : rows.entrySet()) {
            JSONArray a = new JSONArray();
            for (Map<String, String> r : e.getValue()) a.put(mapToJson(r));
            o.put(e.getKey(), a);
        }
        return o;
    }

    private static Map<String, String> jsonToMap(JSONObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o == null) return m;
        Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); m.put(k, o.optString(k, "")); }
        return m;
    }

    private static Map<String, List<Map<String, String>>> jsonToRows(JSONObject o) {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        if (o == null) return rows;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = o.optJSONArray(k);
            List<Map<String, String>> list = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) list.add(jsonToMap(a.optJSONObject(i)));
            rows.put(k, list);
        }
        return rows;
    }
}
