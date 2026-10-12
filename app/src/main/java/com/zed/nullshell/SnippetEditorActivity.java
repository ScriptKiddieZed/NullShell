package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SnippetEditorActivity extends Activity {
    static final String EXTRA_ID = "id";

    private Snippet snippet;
    private boolean isNew;
    private EditText name, group, body;
    private TextView fieldsInfo, sample;
    private Switch enter, confirm, pinned;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SnippetStore.get().init(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        Snippet existing = id == null ? null : SnippetStore.get().find(id);
        isNew = existing == null;
        snippet = isNew ? new Snippet() : existing.copy();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // top bar: back, title, save
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 12), 0);
        TextView back = Ui.iconButton(this, "\u2190");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText(isNew ? "New command" : "Edit command");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = Ui.button(this, "Save", Ui.PRIMARY);
        save.setMinHeight(Ui.dp(this, 36));
        save.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        save.setOnClickListener(v -> save());
        bar.addView(save);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- details card
        LinearLayout card = card(content, "Details");
        name = edit(card, "Name", snippet.name, "e.g. Port scan", false);
        group = edit(card, "Group (optional)", snippet.group, "e.g. Recon", false);
        List<String> groups = SnippetStore.get().groups();
        if (!groups.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setScrollbarFadingEnabled(true);
            LinearLayout chips = new LinearLayout(this);
            for (String g : groups) {
                TextView chip = chip(g, false);
                chip.setOnClickListener(v -> group.setText(g));
                chips.addView(chip);
            }
            hs.addView(chips);
            card.addView(hs);
        }

        // --- command card
        card = card(content, "Command");
        body = edit(card, null, snippet.body, "nmap {sv:-sV} {ports?-p} {target}", true);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setMinLines(4);
        body.setGravity(Gravity.TOP | Gravity.START);

        TextView ins = small("Tap to insert");
        card.addView(ins);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setScrollbarFadingEnabled(true);
        LinearLayout chips = new LinearLayout(this);
        addInsert(chips, "{field}", "{field}", 1, 6);
        addInsert(chips, "{optional?}", "{optional?}", 1, 9);
        addInsert(chips, "{Label:-v}", "{Label:-v}", 1, 6);
        addInsert(chips, "{pick=a|b}", "{pick=a|b}", 1, 5);
        addInsert(chips, "{file@file}", "{file@file}", 1, 5);
        addInsert(chips, "{each: }", "{each: }", 7, 7);
        addInsert(chips, "{url|host}", "{url|host}", 1, 4);
        addInsert(chips, "{out=x_{t|slug}}", "{out=x_{t|slug}}", 1, 4);
        addInsert(chips, "{pass*}", "{pass*}", 1, 5);
        addInsert(chips, "{tags+,:a|b|c}", "{tags+,:a|b|c}", 1, 5);
        addInsert(chips, "{if x: }", "{if x: }", 4, 5);
        addInsert(chips, "{clipboard}", "{clipboard}", 11, 11);
        addInsert(chips, "<Enter>", "<Enter>", 7, 7);
        addInsert(chips, "<C-c>", "<C-c>", 5, 5);
        addInsert(chips, "<sleep 500>", "<sleep 500>", 11, 11);
        hs.addView(chips);
        card.addView(hs);

        fieldsInfo = small("");
        fieldsInfo.setPadding(0, Ui.dp(this, 10), 0, 0);
        card.addView(fieldsInfo);
        sample = new TextView(this);
        sample.setTypeface(Typeface.MONOSPACE);
        sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sample.setTextColor(Ui.ACCENT);
        sample.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(sample);

        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { updatePreview(); }
        });

        // --- behaviour card
        card = card(content, "Behaviour");
        enter = toggle(card, "Press Enter after typing", snippet.enter);
        confirm = toggle(card, "Ask before running", snippet.confirm);
        pinned = toggle(card, "Pin to the keys row", snippet.pinned);

        // --- syntax help
        card = card(content, "Syntax");
        TextView help = small(
                "{name}  required text\n" +
                "{name?}  optional  \u00B7  {name?-p}  optional with prefix (-p VALUE)\n" +
                "{name=default}  text with a default\n" +
                "{name:-v}  toggle, adds -v when on\n" +
                "Names may have spaces: {Service version:-sV}\n" +
                "{name=a|b|c}  choice (first is the default)\n" +
                "{name@file @if Toggle name}  show only while that toggle is on  (@ifnot: while off)\n" +
                "{name@file}  type a path or Browse for a file  \u00B7  {name@dir}  folder\n" +
                "Names with file, wordlist, pcap, path or folder get Browse automatically; {name@none} turns it off\n" +
                "{!name}  raw: not quoted\n" +
                "{each: -a {x} {y}}  repeating group\n" +
                "\nReuse and filters\n" +
                "{url|host}  another use of {url}; chain filters: {url|host|upper}\n" +
                "filters: slug upper lower trim host port path scheme dir name stem ext urlencode\n" +
                "{port=80} {port}  repeating a name shares one field (the richest definition wins)\n" +
                "\nDerived defaults\n" +
                "{out=scan_{target|slug}.txt}  follows other fields until you edit it (Reset to auto)\n" +
                "\nSecrets\n" +
                "{pass*}  {token*?--token}  masked in the form and preview, never saved (not inside {each})\n" +
                "\nMulti-select\n" +
                "{ports+,:22|80|443}  tick several, joined with \",\" (a space if the separator is empty)\n" +
                "{scripts+,?--script=:vuln|safe}  adds the prefix only when something is ticked\n" +
                "{ports+,:*22|80}  a * marks an option ticked by default\n" +
                "\nConditions\n" +
                "{if deep: --script vuln}  {if mode=fast: -T4 |else: -T2}\n" +
                "tests: name  !name  name=value  name!=value  a && b  a || b\n" +
                "{deep:} is a toggle with no flag, handy just for conditions\n" +
                "A field used only inside a branch is shown only while that branch applies\n\n" +
                "{clipboard} {selection} {date} {datetime}\n" +
                "<Enter> <Tab> <Esc> <Up> <C-c> <sleep 500>\n" +
                "{{ and }} for literal braces. ${VAR} and awk '{...}' are left alone.\n" +
                "A new line in the command presses Enter.");
        help.setTypeface(Typeface.MONOSPACE);
        help.setLineSpacing(0, 1.15f);
        card.addView(help);

        if (!isNew) {
            TextView del = Ui.button(this, "Delete command", Ui.DANGER_STYLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 18);
            del.setOnClickListener(v -> new Dlg(this)
                    .setTitle("Delete this command?")
                    .setPositiveButton("Delete", (d, w) -> { SnippetStore.get().delete(snippet.id); finish(); })
                    .setNegativeButton("Cancel", null).show());
            content.addView(del, lp);
        }

        setContentView(root);
        Ui.applyInsets(root);
        updatePreview();
    }

    // ------------------------------------------------------------------ actions

    private void save() {
        String text = body.getText().toString();
        if (text.trim().isEmpty()) { Toast.makeText(this, "Write a command first", Toast.LENGTH_SHORT).show(); return; }
        String n = name.getText().toString().trim();
        if (n.isEmpty()) {
            String first = text.trim().split("\n")[0];
            n = first.length() > 28 ? first.substring(0, 28) + "\u2026" : first;
        }
        snippet.name = n;
        snippet.group = group.getText().toString().trim();
        snippet.body = text;
        snippet.enter = enter.isChecked();
        snippet.confirm = confirm.isChecked();
        snippet.pinned = pinned.isChecked();
        SnippetStore.get().put(snippet);
        finish();
    }

    private void updatePreview() {
        SnippetTemplate t = SnippetTemplate.parse(body.getText().toString());
        if (!t.hasFields()) {
            fieldsInfo.setText("No fields: runs exactly as written.");
        } else {
            List<String> parts = new ArrayList<>();
            for (SnippetTemplate.Field f : t.fields()) {
                String kind = f.kind == SnippetTemplate.TOGGLE ? "toggle" : f.kind == SnippetTemplate.CHOICE ? "choice"
                        : f.kind == SnippetTemplate.MULTI ? "multi-select" : f.secret ? "secret" : f.dynamic() ? "auto default"
                        : f.pick == SnippetTemplate.PICK_FILE ? "file" : f.pick == SnippetTemplate.PICK_DIR ? "folder"
                        : f.required() ? "required" : "optional";
                parts.add(f.name + " (" + kind + ")");
            }
            for (SnippetTemplate.Group g : t.groups()) parts.add("repeating group (" + g.fields.size() + " fields)");
            fieldsInfo.setText("Form: " + String.join("  \u00B7  ", parts));
        }
        List<String> problems = t.problems();
        if (!problems.isEmpty()) fieldsInfo.setText(fieldsInfo.getText() + "\n\u26A0 " + String.join("\n\u26A0 ", problems));

        Map<String, String> values = new LinkedHashMap<>();
        for (SnippetTemplate.Field f : t.fields()) if (!f.dynamic()) values.put(f.name, sampleValue(f));
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        for (SnippetTemplate.Group g : t.groups()) {
            Map<String, String> r = new LinkedHashMap<>();
            for (SnippetTemplate.Field f : g.fields) r.put(f.name, sampleValue(f));
            List<Map<String, String>> l = new ArrayList<>();
            l.add(r);
            rows.put(g.id, l);
        }
        Map<String, String> bi = new LinkedHashMap<>();
        bi.put("clipboard", "CLIPBOARD");
        bi.put("selection", "SELECTION");
        bi.put("date", "DATE");
        bi.put("datetime", "DATETIME");
        String out = KeyTokens.stripTokens(t.render(values, rows, bi, true));
        sample.setText(out.trim().isEmpty() ? "" : "Example: " + out);
    }

    private static String sampleValue(SnippetTemplate.Field f) {
        if (f.kind == SnippetTemplate.TOGGLE) return "true";
        if (f.kind == SnippetTemplate.MULTI) return f.options.isEmpty() ? "" : f.options.get(0);
        if (f.secret) return "secret";
        if (f.kind == SnippetTemplate.CHOICE) return f.options.isEmpty() ? "" : f.options.get(f.options.size() > 1 ? 1 : 0);
        return f.def.isEmpty() ? f.name.toUpperCase(Locale.US) : f.def;
    }

    // ------------------------------------------------------------------ widgets

    private LinearLayout card(LinearLayout parent, String title) {
        TextView h = new TextView(this);
        h.setText(title.toUpperCase(Locale.US));
        h.setTextColor(Ui.ACCENT);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        h.setLetterSpacing(0.1f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(Ui.dp(this, 6), Ui.dp(this, 20), 0, Ui.dp(this, 8));
        parent.addView(h);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        int p = Ui.dp(this, 14);
        c.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 14));
        parent.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private TextView small(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        return t;
    }

    private EditText edit(LinearLayout parent, String label, String value, String hint, boolean multiline) {
        if (label != null) parent.addView(small(label));
        EditText e = new EditText(this);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.HINT);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        e.setText(value);
        Ui.tint(e);
        parent.addView(e);
        return e;
    }

    private Switch toggle(LinearLayout parent, String title, boolean on) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setChecked(on);
        sw.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        Ui.tint(sw);
        parent.addView(sw);
        return sw;
    }

    private TextView chip(String text, boolean mono) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.ripple(this, Ui.SURFACE2, 8));
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** Adds a chip that inserts text at the cursor and selects [selFrom, selTo) inside it. */
    private void addInsert(LinearLayout row, String label, String text, int selFrom, int selTo) {
        TextView c = chip(label, true);
        c.setOnClickListener(v -> {
            int pos = Math.max(0, body.getSelectionStart());
            Editable e = body.getText();
            e.insert(pos, text);
            body.setSelection(Math.min(e.length(), pos + selFrom), Math.min(e.length(), pos + Math.max(selFrom, selTo)));
            body.requestFocus();
        });
        row.addView(c);
    }
}
