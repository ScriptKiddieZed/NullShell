package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Fetches the model list from the Gemini API (cached for a day) and lets the person pick one. */
final class ModelPicker {
    private ModelPicker() { }

    interface Callback { void onPick(String modelId); }   // "" = use the default

    static final String K_CACHE = "ai_models_cache", K_TIME = "ai_models_time", K_DEFAULT = "ai_model";
    private static final long MAX_AGE = 24L * 3600 * 1000;

    static String defaultModel(android.content.Context c) {
        String m = Prefs.sp(c).getString(K_DEFAULT, "");
        return m.isEmpty() ? GeminiClient.DEFAULT_MODEL : m;
    }

    static void setDefaultModel(android.content.Context c, String id) { Prefs.sp(c).edit().putString(K_DEFAULT, id).apply(); }

    private static boolean chatCapable(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        return !(s.contains("tts") || s.contains("image") || s.contains("-live") || s.contains("audio") || s.contains("embedding")
                || s.contains("imagen") || s.contains("veo") || s.contains("robotics") || s.contains("computer-use"));
    }

    private static List<GeminiClient.Model> cached(android.content.Context c) {
        List<GeminiClient.Model> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(K_CACHE, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new GeminiClient.Model(o.getString("id"), o.optString("d", o.getString("id")), o.optInt("in", 0), o.optInt("out", 0)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void store(android.content.Context c, List<GeminiClient.Model> list) {
        try {
            JSONArray a = new JSONArray();
            for (GeminiClient.Model m : list) a.put(new JSONObject().put("id", m.id).put("d", m.display).put("in", m.inputLimit).put("out", m.outputLimit));
            Prefs.sp(c).edit().putString(K_CACHE, a.toString()).putLong(K_TIME, System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    private static List<GeminiClient.Model> sorted(List<GeminiClient.Model> in) {
        List<GeminiClient.Model> out = new ArrayList<>();
        for (GeminiClient.Model m : in) if (chatCapable(m.id)) out.add(m);
        Collections.sort(out, (a, b) -> {
            boolean la = a.id.endsWith("-latest"), lb = b.id.endsWith("-latest");
            if (la != lb) return la ? -1 : 1;
            return b.id.compareTo(a.id);
        });
        return out;
    }

    /**
     * @param current   the model now in use for this choice ("" when the default applies)
     * @param defaultRowLabel when non-null, a first row that clears the choice ("Use default (…)")
     */
    static void show(Activity act, String current, String defaultRowLabel, Callback cb) {
        final String key = KeyVault.get(act);
        if (key.isEmpty()) { AiUi.toast(act, "Add your Gemini API key in AI settings first."); return; }

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AiUi.dp(act, 18), AiUi.dp(act, 4), AiUi.dp(act, 18), 0);
        final EditText filter = AiUi.field(act, "Filter models", "", 1);
        box.addView(filter);
        final TextView status = new TextView(act);
        status.setTextColor(Ui.DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, AiUi.dp(act, 6), 0, AiUi.dp(act, 4));
        box.addView(status);
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AiUi.dp(act, 330)));

        final AlertDialog[] dlg = new AlertDialog[1];
        final List<GeminiClient.Model>[] data = new List[]{sorted(cached(act))};

        final Runnable fill = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (defaultRowLabel != null && q.isEmpty()) list.addView(row(act, defaultRowLabel, "", current.isEmpty(), () -> { dlg[0].dismiss(); cb.onPick(""); }));
                for (GeminiClient.Model m : data[0]) {
                    if (!q.isEmpty() && !m.id.toLowerCase(Locale.ROOT).contains(q) && !m.display.toLowerCase(Locale.ROOT).contains(q)) continue;
                    String sub = m.id + (m.inputLimit > 0 ? "  ·  " + (m.inputLimit / 1000) + "k context" : "");
                    list.addView(row(act, m.display, sub, m.id.equals(current), () -> { dlg[0].dismiss(); cb.onPick(m.id); }));
                }
                if (list.getChildCount() == 0) {
                    TextView t = new TextView(act);
                    t.setText(data[0].isEmpty() ? "No models loaded yet." : "No match. Use “Custom id” to type one.");
                    t.setTextColor(Ui.DIM);
                    t.setPadding(0, AiUi.dp(act, 12), 0, 0);
                    list.addView(t);
                }
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { fill.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            status.setText("Loading models…");
            new Thread(() -> {
                try {
                    List<GeminiClient.Model> got = GeminiClient.listModels(key);
                    store(act, got);
                    act.runOnUiThread(() -> {
                        data[0] = sorted(got);
                        status.setText(data[0].size() + " models (updated just now)");
                        fill.run();
                    });
                } catch (IOException e) {
                    final String msg = GeminiClient.describe(e);
                    act.runOnUiThread(() -> status.setText("Couldn't refresh: " + msg + (data[0].isEmpty() ? "" : "\nShowing the saved list.")));
                }
            }, "models").start();
        };

        long age = System.currentTimeMillis() - Prefs.sp(act).getLong(K_TIME, 0);
        if (data[0].isEmpty() || age > MAX_AGE) refresh[0].run();
        else status.setText(data[0].size() + " models (saved list)");
        fill.run();

        dlg[0] = new Dlg(act).setTitle("Choose a model").setView(box)
                .setNegativeButton("Close", null)
                .setNeutralButton("Refresh", null)
                .setPositiveButton("Custom id", null).create();
        dlg[0].show();
        dlg[0].getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh[0].run());
        dlg[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dlg[0].dismiss();
            Ui.prompt(act, "Model id", "e.g. gemini-flash-lite-latest", current, "Use", t -> {
                String id = t.trim();
                if (id.startsWith("models/")) id = id.substring(7);
                if (!id.isEmpty()) cb.onPick(id);
            });
        });
        Dlg.style(dlg[0]);
    }

    private static View row(Activity act, String title, String sub, boolean selected, Runnable click) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(AiUi.dp(act, 10), AiUi.dp(act, 9), AiUi.dp(act, 10), AiUi.dp(act, 9));
        r.setBackground(selected ? Ui.rounded(act, Ui.SURFACE, 10) : Ui.ripple(act, 0, 10));
        r.setClickable(true);
        TextView t = new TextView(act);
        t.setText((selected ? "✓  " : "") + title);
        t.setTextColor(selected ? Ui.ACCENT : Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        r.addView(t);
        if (!sub.isEmpty()) {
            TextView s = new TextView(act);
            s.setText(sub);
            s.setTextColor(Ui.DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            r.addView(s);
        }
        r.setOnClickListener(v -> click.run());
        return r;
    }
}
