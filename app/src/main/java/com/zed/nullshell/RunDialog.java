package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The form for a template snippet: one control per field, repeating rows, and an editable live preview. */
final class RunDialog {
    interface Actions {
        void run(String text, boolean enter);
        void insert(String text);
        void copy(String text);
    }

    /** ScrollView that never grows past a maximum height, so the buttons below stay on screen. */
    private static final class MaxHeightScroll extends ScrollView {
        private final int maxHeight;
        MaxHeightScroll(Context c, int maxHeight) { super(c); this.maxHeight = maxHeight; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        }
    }

    static void show(Activity act, Snippet snippet, Map<String, String> builtins, Actions actions) {
        new RunDialog(act, snippet, builtins, actions).open();
    }

    private final Activity act;
    private final Snippet snip;
    private final SnippetTemplate tpl;
    private final Map<String, String> builtins;
    private final Actions actions;

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();

    private Dialog dialog;
    private LinearLayout form;
    private EditText preview;
    private TextView resetLink, missingView, runBtn;
    private boolean userEdited, programmatic;

    private RunDialog(Activity act, Snippet snip, Map<String, String> builtins, Actions actions) {
        this.act = act;
        this.snip = snip;
        this.tpl = snip.template();
        this.builtins = builtins;
        this.actions = actions;
    }

    // ------------------------------------------------------------------ state

    private String initial(SnippetTemplate.Field f, String saved, Map<String, String> own) {
        switch (f.kind) {
            case SnippetTemplate.TOGGLE: return "true".equals(saved) ? "true" : "false";
            case SnippetTemplate.CHOICE:
                return saved != null && f.options.contains(saved) ? saved : (f.options.isEmpty() ? "" : f.options.get(0));
            case SnippetTemplate.MULTI:
                return saved == null ? f.def : SnippetTemplate.join(f, SnippetTemplate.selected(saved));
            default:
                if (f.secret) return "";                                   // secrets are never restored
                return saved != null ? saved : tpl.defaultFor(f, own, values, builtins);
        }
    }

    /** Derived defaults need the other fields filled in first, so settle them in a few passes. */
    private void settle(List<SnippetTemplate.Field> fs, Map<String, String> own, Map<String, String> saved) {
        for (int pass = 0; pass < 3; pass++)
            for (SnippetTemplate.Field f : fs)
                if (f.kind == SnippetTemplate.TEXT && f.dynamic() && (saved == null || saved.get(f.name) == null))
                    own.put(f.name, tpl.defaultFor(f, own, values, builtins));
    }

    private Map<String, String> newRow(SnippetTemplate.Group g, Map<String, String> saved, Map<String, String> inheritFrom) {
        Map<String, String> r = new LinkedHashMap<>();
        for (SnippetTemplate.Field f : g.fields) {
            String s = saved != null ? saved.get(f.name) : null;
            if (s == null && inheritFrom != null && f.kind != SnippetTemplate.TEXT) s = inheritFrom.get(f.name);
            r.put(f.name, initial(f, s, r));
        }
        settle(g.fields, r, saved);
        return r;
    }

    private void initState() {
        values.clear();
        rows.clear();
        for (SnippetTemplate.Field f : tpl.fields()) values.put(f.name, initial(f, snip.lastValues.get(f.name), values));
        settle(tpl.fields(), values, snip.lastValues);
        for (SnippetTemplate.Group g : tpl.groups()) {
            List<Map<String, String>> list = new ArrayList<>();
            List<Map<String, String>> saved = snip.lastRows.get(g.id);
            if (saved != null) for (Map<String, String> r : saved) list.add(newRow(g, r, null));
            if (list.isEmpty()) list.add(newRow(g, null, null));
            rows.put(g.id, list);
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) { return Ui.dp(act, v); }

    private TextView label(String s, boolean dim) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(dim ? Ui.DIM : Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, dim ? 12 : 14);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private void open() {
        initState();
        DisplayMetrics dm = act.getResources().getDisplayMetrics();

        dialog = new Dialog(act, R.style.AppDialog);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(Ui.rounded(act, Ui.SURFACE2, 16));
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(14));

        // header: name + presets
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText(snip.name.isEmpty() ? "Command" : snip.name);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (tpl.hasFields()) {
            TextView presets = Ui.button(act, "Presets", Ui.SECONDARY);
            presets.setMinHeight(dp(34));
            presets.setPadding(dp(12), 0, dp(12), 0);
            presets.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            presets.setOnClickListener(v -> showPresets());
            head.addView(presets);
        }
        root.addView(head);

        // fields (scrolls) + preview
        MaxHeightScroll scroll = new MaxHeightScroll(act, (int) (dm.heightPixels * 0.5f));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        form = new LinearLayout(act);
        form.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(form, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout previewHead = new LinearLayout(act);
        previewHead.setOrientation(LinearLayout.HORIZONTAL);
        TextView pl = label("Command  \u00B7  editable", true);
        previewHead.addView(pl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resetLink = label("Reset", true);
        resetLink.setTextColor(Ui.ACCENT);
        resetLink.setVisibility(View.GONE);
        resetLink.setOnClickListener(v -> { userEdited = false; refresh(); });
        previewHead.addView(resetLink);
        root.addView(previewHead);

        preview = new EditText(act);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        preview.setTextColor(Ui.TEXT);
        preview.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        preview.setMinLines(2);
        preview.setGravity(Gravity.TOP | Gravity.START);
        preview.setBackground(Ui.rounded(act, Ui.BG, 8));
        preview.setPadding(dp(10), dp(8), dp(10), dp(8));
        preview.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (programmatic) return;
                userEdited = true;
                resetLink.setVisibility(View.VISIBLE);
                updateMissing();
            }
        });
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        missingView = new TextView(act);
        missingView.setTextColor(Ui.DANGER);
        missingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        missingView.setPadding(0, dp(6), 0, 0);
        root.addView(missingView);

        // actions
        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = Ui.button(act, "Copy", Ui.SECONDARY);
        TextView insert = Ui.button(act, "Insert", Ui.SECONDARY);
        runBtn = Ui.button(act, "Run", Ui.PRIMARY);
        copy.setOnClickListener(v -> act(2));
        insert.setOnClickListener(v -> act(1));
        runBtn.setOnClickListener(v -> act(0));
        for (TextView b : new TextView[]{copy, insert, runBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(lp);
            buttons.addView(b);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        root.addView(buttons, blp);

        buildForm();

        dialog.setContentView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (dm.widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void buildForm() {
        form.removeAllViews();
        conditional.clear();
        dyn.clear();
        for (SnippetTemplate.Field f : tpl.fields()) addFieldWidget(form, f, values);
        for (SnippetTemplate.Group g : tpl.groups()) addGroup(g);
        if (!tpl.hasFields()) {
            TextView t = label("No fields. This command runs exactly as written.", true);
            form.addView(t);
        }
        refresh();
    }

    private final List<Object[]> conditional = new ArrayList<>();      // {Field, wrapper view, value map}

    /** A text field whose default is built from other fields: follows them until the user types in it. */
    private static final class Dyn {
        SnippetTemplate.Field f; EditText e; Map<String, String> map; View owner; TextView reset; boolean touched;
    }
    private final List<Dyn> dyn = new ArrayList<>();
    private boolean autoSet;

    private void addFieldWidget(LinearLayout parent, SnippetTemplate.Field f, Map<String, String> map) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        addFieldWidgetNow(wrap, f, map);
        parent.addView(wrap);
        conditional.add(new Object[]{f, wrap, map});
        wrap.setVisibility(tpl.visible(f, map, values, builtins) ? View.VISIBLE : View.GONE);
    }

    private void applyVisibility() {
        for (Object[] c : conditional) {
            @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) c[2];
            ((View) c[1]).setVisibility(tpl.visible((SnippetTemplate.Field) c[0], m, values, builtins) ? View.VISIBLE : View.GONE);
        }
    }

    private void addFieldWidgetNow(LinearLayout parent, SnippetTemplate.Field f, Map<String, String> map) {
        if (f.kind == SnippetTemplate.TOGGLE) {
            Switch sw = new Switch(act);
            sw.setText(f.label() + (f.flag.isEmpty() ? "" : "   " + f.flag));
            sw.setTextColor(Ui.TEXT);
            sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sw.setPadding(0, dp(8), 0, dp(8));
            Ui.tint(sw);
            sw.setChecked("true".equals(map.get(f.name)));
            sw.setOnCheckedChangeListener((v, on) -> { map.put(f.name, on ? "true" : "false"); refresh(); });
            parent.addView(sw);
        } else if (f.kind == SnippetTemplate.MULTI) {
            parent.addView(label(f.label(), true));
            for (final String opt : f.options) {
                CheckBox cb = new CheckBox(act);
                cb.setText(opt);
                cb.setTextColor(Ui.TEXT);
                cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
                cb.setChecked(SnippetTemplate.selected(map.get(f.name)).contains(opt));
                cb.setOnCheckedChangeListener((b, on) -> {
                    List<String> cur = SnippetTemplate.selected(map.get(f.name));
                    if (on) { if (!cur.contains(opt)) cur.add(opt); } else cur.remove(opt);
                    map.put(f.name, SnippetTemplate.join(f, cur));
                    refresh();
                });
                parent.addView(cb);
            }
        } else if (f.kind == SnippetTemplate.CHOICE) {
            parent.addView(label(f.label(), true));
            final TextView pick = Ui.button(act, choiceLabel(map.get(f.name)), Ui.SECONDARY);
            pick.setOnClickListener(v -> {
                String[] items = new String[f.options.size()];
                int checked = 0;
                for (int i = 0; i < items.length; i++) {
                    items[i] = choiceLabel(f.options.get(i));
                    if (f.options.get(i).equals(map.get(f.name))) checked = i;
                }
                new Dlg(act).setTitle(f.label())
                        .setSingleChoiceItems(items, checked, (d, which) -> {
                            map.put(f.name, f.options.get(which));
                            pick.setText(choiceLabel(f.options.get(which)));
                            refresh();
                            d.dismiss();
                        }).show();
            });
            parent.addView(pick);
        } else {
            final Dyn dy = f.dynamic() ? new Dyn() : null;
            LinearLayout labelRow = new LinearLayout(act);
            labelRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView lab = label(f.label() + (f.required() ? "  *" : f.optional ? "  (optional)" : f.secret ? "  (hidden, not saved)" : ""), true);
            labelRow.addView(lab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (dy != null) {
                dy.reset = label("Reset to auto", true);
                dy.reset.setTextColor(Ui.ACCENT);
                dy.reset.setVisibility(View.GONE);
                labelRow.addView(dy.reset);
            }
            parent.addView(labelRow);
            final EditText e = new EditText(act);
            e.setSingleLine(true);
            e.setInputType(f.secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setTextColor(Ui.TEXT);
            e.setHintTextColor(Ui.HINT);
            e.setHint(f.pick != SnippetTemplate.PICK_NONE ? (f.pick == SnippetTemplate.PICK_DIR ? "Type a folder path or Browse" : "Type a file path or Browse")
                    : f.prefix.isEmpty() ? "" : f.prefix);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            e.setText(map.get(f.name));
            Ui.tint(e);
            e.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    if (autoSet) return;
                    map.put(f.name, s.toString());
                    if (dy != null && !dy.touched) { dy.touched = true; dy.reset.setVisibility(View.VISIBLE); }
                    refresh();
                }
            });
            if (dy != null) {
                dy.f = f; dy.e = e; dy.map = map; dy.owner = currentOwner;
                dy.touched = !String.valueOf(map.get(f.name)).equals(tpl.defaultFor(f, map, values, builtins));
                dy.reset.setVisibility(dy.touched ? View.VISIBLE : View.GONE);
                dy.reset.setOnClickListener(v -> { dy.touched = false; dy.reset.setVisibility(View.GONE); refresh(); });
                dyn.add(dy);
            }
            if (f.secret) {
                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                final TextView show = Ui.button(act, "Show", Ui.SECONDARY);
                show.setMinHeight(dp(38));
                show.setPadding(dp(12), 0, dp(12), 0);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sp.setMargins(dp(8), 0, 0, 0);
                show.setLayoutParams(sp);
                final boolean[] shown = {false};
                show.setOnClickListener(v -> {
                    shown[0] = !shown[0];
                    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            | (shown[0] ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    e.setSelection(e.getText().length());
                    show.setText(shown[0] ? "Hide" : "Show");
                });
                line.addView(show);
                parent.addView(line);
            } else if (f.pick == SnippetTemplate.PICK_NONE) {
                parent.addView(e);
            } else {
                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView browse = Ui.button(act, "Browse", Ui.SECONDARY);
                browse.setMinHeight(dp(38));
                browse.setPadding(dp(12), 0, dp(12), 0);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.setMargins(dp(8), 0, 0, 0);
                browse.setLayoutParams(bp);
                browse.setOnClickListener(v -> {
                    boolean folder = f.pick == SnippetTemplate.PICK_DIR;
                    FilePick.request(act, folder, pickStart(e.getText().toString(), folder), path -> e.setText(path));
                });
                line.addView(browse);
                parent.addView(line);
            }
        }
    }

    /** Where the picker opens: the folder of what is already typed, else the last place used. */
    private String pickStart(String typed, boolean folder) {
        String t = typed == null ? "" : typed.trim();
        if (t.startsWith("/")) {
            String dir = folder ? t : GuestPaths.parent(t);
            File host = GuestPaths.toHost(RootfsManager.dir(act), dir, true);
            if (host != null && host.isDirectory()) return GuestPaths.normalize(dir);
        }
        return null;
    }

    private static String choiceLabel(String v) { return v == null || v.isEmpty() ? "(none)" : v; }

    private void addGroup(SnippetTemplate.Group g) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        form.addView(label("Repeated entries", true));
        form.addView(box);
        renderRows(g, box);
    }

    private View currentOwner;

    private void renderRows(SnippetTemplate.Group g, LinearLayout box) {
        box.removeAllViews();
        dyn.removeIf(d -> d.owner == box);
        currentOwner = box;
        List<Map<String, String>> list = rows.get(g.id);
        for (int i = 0; i < list.size(); i++) {
            final Map<String, String> row = list.get(i);
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.rounded(act, Ui.SURFACE, 10));
            card.setPadding(dp(12), dp(4), dp(12), dp(10));

            LinearLayout top = new LinearLayout(act);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = label("#" + (i + 1), true);
            top.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (list.size() > 1) {
                TextView rm = label("Remove", true);
                rm.setTextColor(Ui.DANGER);
                rm.setPadding(dp(8), dp(10), 0, dp(2));
                rm.setOnClickListener(v -> { list.remove(row); renderRows(g, box); refresh(); });
                top.addView(rm);
            }
            card.addView(top);
            for (SnippetTemplate.Field f : g.fields) addFieldWidget(card, f, row);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            box.addView(card, lp);
        }
        currentOwner = null;
        TextView add = Ui.button(act, "+ Add another", Ui.SECONDARY);
        add.setOnClickListener(v -> {
            list.add(newRow(g, null, list.isEmpty() ? null : list.get(list.size() - 1)));
            renderRows(g, box);
            refresh();
        });
        box.addView(add);
    }

    // ------------------------------------------------------------------ preview & actions

    private void syncDynamic() {
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            for (Dyn d : dyn) {
                if (d.touched) continue;
                String exp = tpl.defaultFor(d.f, d.map, values, builtins);
                if (!exp.equals(d.map.get(d.f.name))) {
                    d.map.put(d.f.name, exp);
                    autoSet = true;
                    d.e.setText(exp);
                    autoSet = false;
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private void refresh() {
        syncDynamic();
        applyVisibility();
        if (!userEdited) {
            programmatic = true;
            preview.setText(tpl.render(values, rows, builtins, true));
            programmatic = false;
            resetLink.setVisibility(View.GONE);
        }
        updateMissing();
    }

    private void updateMissing() {
        List<String> miss = tpl.missing(values, rows, builtins);
        boolean block = !miss.isEmpty() && !userEdited;
        missingView.setText(block ? "Fill in: " + String.join(", ", miss) : "");
        runBtn.setAlpha(block ? 0.45f : 1f);
    }

    private void act(int which) {
        String shown = preview.getText().toString();               // secrets appear masked here
        String text = userEdited ? tpl.unmask(shown, values) : tpl.render(values, rows, builtins, false);
        List<String> miss = tpl.missing(values, rows, builtins);
        if (which == 0 && !userEdited && !miss.isEmpty()) {
            Toast.makeText(act, "Fill in: " + String.join(", ", miss), Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.trim().isEmpty()) {
            Toast.makeText(act, "Nothing to use yet", Toast.LENGTH_SHORT).show();
            return;
        }
        remember();
        if (which == 0 && snip.confirm) {
            new Dlg(act).setTitle("Run this command?").setMessage(shown)
                    .setPositiveButton("Run", (d, w) -> perform(0, text))
                    .setNegativeButton("Cancel", null).show();
        } else {
            perform(which, text);
        }
    }

    private void perform(int which, String text) {
        dialog.dismiss();
        if (which == 0) actions.run(text, snip.enter);
        else if (which == 1) actions.insert(text);
        else actions.copy(text);
    }

    private void remember() {
        snip.lastValues = new LinkedHashMap<>(values);
        for (SnippetTemplate.Field s : tpl.secrets()) snip.lastValues.remove(s.name);          // never stored
        Map<String, List<Map<String, String>>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, String>>> e : rows.entrySet()) {
            List<Map<String, String>> l = new ArrayList<>();
            for (Map<String, String> r : e.getValue()) l.add(new LinkedHashMap<>(r));
            copy.put(e.getKey(), l);
        }
        snip.lastRows = copy;
        SnippetStore.get().put(snip);
    }

    // ------------------------------------------------------------------ presets

    private void showPresets() {
        final Dialog d = new Dialog(act, R.style.AppDialog);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.rounded(act, Ui.SURFACE2, 16));
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(14));
        TextView t = new TextView(act);
        t.setText("Presets");
        t.setTextColor(Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(t);

        if (snip.presets.isEmpty()) {
            TextView none = label("No presets yet. Fill in the form, then save it here.", true);
            root.addView(none);
        }
        for (int i = 0; i < snip.presets.size(); i++) {
            final Snippet.Preset p = snip.presets.get(i);
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(act);
            name.setText(p.name);
            name.setTextColor(Ui.TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setPadding(0, dp(12), 0, dp(12));
            name.setOnClickListener(v -> { loadPreset(p); d.dismiss(); });
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = new TextView(act);
            del.setText("\u00D7");
            del.setTextColor(Ui.DIM);
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            del.setPadding(dp(14), dp(8), dp(4), dp(8));
            del.setOnClickListener(v -> {
                snip.presets.remove(p);
                SnippetStore.get().put(snip);
                d.dismiss();
                showPresets();
            });
            row.addView(del);
            root.addView(row);
        }

        TextView save = Ui.button(act, "Save current as preset\u2026", Ui.PRIMARY);
        save.setOnClickListener(v -> { d.dismiss(); askPresetName(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(save, lp);

        d.setContentView(root);
        d.show();
        if (d.getWindow() != null) d.getWindow().setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.9f), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void askPresetName() {
        final EditText name = new EditText(act);
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHint("e.g. quick scan");
        name.setHintTextColor(Ui.HINT);
        Ui.tint(name);
        new Dlg(act).setTitle("Preset name").setView(name)
                .setPositiveButton("Save", (dd, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    Snippet.Preset p = new Snippet.Preset();
                    p.name = n;
                    p.values = new LinkedHashMap<>(values);
                    for (SnippetTemplate.Field sf : tpl.secrets()) p.values.remove(sf.name);
                    for (Map.Entry<String, List<Map<String, String>>> e : rows.entrySet()) {
                        List<Map<String, String>> l = new ArrayList<>();
                        for (Map<String, String> r : e.getValue()) l.add(new LinkedHashMap<>(r));
                        p.rows.put(e.getKey(), l);
                    }
                    snip.presets.removeIf(x -> x.name.equalsIgnoreCase(n));
                    snip.presets.add(p);
                    SnippetStore.get().put(snip);
                    Toast.makeText(act, "Preset saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void loadPreset(Snippet.Preset p) {
        for (SnippetTemplate.Field f : tpl.fields()) if (!f.secret) values.put(f.name, initial(f, p.values.get(f.name), values));
        settle(tpl.fields(), values, p.values);
        for (SnippetTemplate.Group g : tpl.groups()) {
            List<Map<String, String>> list = new ArrayList<>();
            List<Map<String, String>> saved = p.rows.get(g.id);
            if (saved != null) for (Map<String, String> r : saved) list.add(newRow(g, r, null));
            if (list.isEmpty()) list.add(newRow(g, null, null));
            rows.put(g.id, list);
        }
        userEdited = false;
        buildForm();
    }
}
