package com.zed.nullshell;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Content of the right-hand drawer: title row with a "+" button, a search field, and the saved
 * snippets grouped by their group name. Sliding it in and out is done by the host activity.
 */
final class DrawerPanel extends LinearLayout {
    interface Host {
        void onTap(Snippet s);              // run (or open the form)
        void onInsert(Snippet s);           // type without Enter
        void onEdit(Snippet s);
        void onNew();
        void onChanged();                   // pins changed, duplicates, deletes...
    }

    private final Host host;
    private final LinearLayout list;
    private final TextView empty;
    private final EditText search;
    private final Set<String> collapsed = new HashSet<>();

    DrawerPanel(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.SURFACE);
        setClickable(true);                 // swallow touches so the terminal below does not react

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 4), 0);
        TextView title = new TextView(c);
        title.setText("Commands");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.iconButton(c, "+");
        add.setOnClickListener(v -> host.onNew());
        head.addView(add);
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 50)));

        search = new EditText(c);
        search.setSingleLine(true);
        search.setHint("Search");
        search.setHintTextColor(Ui.HINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Ui.rounded(c, Ui.BG, 10));
        search.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        slp.setMargins(Ui.dp(c, 12), Ui.dp(c, 2), Ui.dp(c, 12), Ui.dp(c, 8));
        addView(search, slp);

        ScrollView scroll = new ScrollView(c);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(c, 16));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(c);
        empty.setTextColor(Ui.DIM);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(c, 24), Ui.dp(c, 40), Ui.dp(c, 24), 0);
    }

    void clearSearch() { search.setText(""); }

    void refresh() {
        Context c = getContext();
        list.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.US);
        List<Snippet> all = SnippetStore.get().all();
        String lastGroup = null;
        int shown = 0;
        for (Snippet s : all) {
            if (!q.isEmpty() && !(s.name.toLowerCase(Locale.US).contains(q)
                    || s.body.toLowerCase(Locale.US).contains(q) || s.group.toLowerCase(Locale.US).contains(q))) continue;
            shown++;
            String g = s.group;
            if (lastGroup == null || !lastGroup.equals(g)) {
                lastGroup = g;
                if (!g.isEmpty()) list.addView(groupHeader(g, q.isEmpty()));
            }
            if (!g.isEmpty() && q.isEmpty() && collapsed.contains(g)) continue;
            list.addView(row(s));
        }
        if (shown == 0) {
            empty.setText(all.isEmpty()
                    ? "Nothing here yet.\nTap + to save a command or a template with {fields}."
                    : "No matches.");
            if (empty.getParent() != null) ((ViewGroup) empty.getParent()).removeView(empty);
            list.addView(empty);
        }
    }

    private View groupHeader(String name, boolean collapsible) {
        Context c = getContext();
        TextView h = new TextView(c);
        boolean isCollapsed = collapsed.contains(name);
        h.setText((collapsible ? (isCollapsed ? "\u25B8  " : "\u25BE  ") : "") + name.toUpperCase(Locale.US));
        h.setTextColor(Ui.ACCENT);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        h.setLetterSpacing(0.1f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 6));
        if (collapsible) h.setOnClickListener(v -> {
            if (!collapsed.remove(name)) collapsed.add(name);
            refresh();
        });
        return h;
    }

    private View row(Snippet s) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(VERTICAL);
        row.setPadding(Ui.dp(c, 16), Ui.dp(c, 9), Ui.dp(c, 16), Ui.dp(c, 9));
        row.setBackground(Ui.ripple(c, 0, 0));
        row.setClickable(true);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(s.name.isEmpty() ? "(unnamed)" : s.name);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s.template().hasFields()) top.addView(badge("form"));
        if (s.pinned) top.addView(badge("pinned"));
        row.addView(top);

        TextView body = new TextView(c);
        body.setText(s.body.replace('\n', '\u21B5'));
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(Ui.DIM);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setSingleLine(true);
        body.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(body);

        row.setOnClickListener(v -> host.onTap(s));
        row.setOnLongClickListener(v -> { showMenu(row, s); return true; });
        return row;
    }

    private View badge(String text) {
        Context c = getContext();
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(Ui.DIM);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        b.setBackground(Ui.rounded(c, Ui.SURFACE2, 6));
        b.setPadding(Ui.dp(c, 6), Ui.dp(c, 1), Ui.dp(c, 6), Ui.dp(c, 1));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(c, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private void showMenu(View anchor, Snippet s) {
        TPopup pm = new TPopup(getContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Edit");
        pm.getMenu().add(0, 2, 1, "Insert (don't run)");
        pm.getMenu().add(0, 3, 2, s.pinned ? "Unpin from keys row" : "Pin to keys row");
        pm.getMenu().add(0, 4, 3, "Duplicate");
        pm.getMenu().add(0, 5, 4, "Delete");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: host.onEdit(s); return true;
                case 2: host.onInsert(s); return true;
                case 3: s.pinned = !s.pinned; SnippetStore.get().put(s); host.onChanged(); refresh(); return true;
                case 4: SnippetStore.get().duplicate(s.id); host.onChanged(); refresh(); return true;
                case 5: confirmDelete(s); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void confirmDelete(Snippet s) {
        new Dlg(getContext())
                .setTitle("Delete \"" + s.name + "\"?")
                .setPositiveButton("Delete", (d, w) -> { SnippetStore.get().delete(s.id); host.onChanged(); refresh(); })
                .setNegativeButton("Cancel", null).show();
    }
}
