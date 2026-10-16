package com.zed.nullshell;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Content of the left-hand drawer: every terminal session (the console first) with its state, and buttons to rename or
 * close it. Sliding it in and out is done by the host activity.
 */
final class SessionsPanel extends LinearLayout {
    interface Host {
        void onSelect(KaliSession s);
        void onRename(KaliSession s);
        void onClose(KaliSession s);
        void onNew();
    }

    private final Host host;
    private final LinearLayout list;

    SessionsPanel(Context c, Host host) {
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
        title.setText("Sessions");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.iconButton(c, "+");
        add.setOnClickListener(v -> host.onNew());
        head.addView(add);
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 50)));

        ScrollView scroll = new ScrollView(c);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 16));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(c);
        hint.setText("Tap to switch. Long-press a session or use the pencil to rename it.");
        hint.setTextColor(Ui.DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 14));
        addView(hint);
    }

    void refresh(List<KaliSession> all, KaliSession active) {
        Context c = getContext();
        list.removeAllViews();
        for (KaliSession s : all) list.addView(row(c, s, s == active));
    }

    private View row(Context c, KaliSession s, boolean active) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 2), 0);
        row.setBackground(Ui.ripple(c, active ? Ui.SURFACE2 : 0, 10));
        row.setClickable(true);
        row.setOnClickListener(v -> host.onSelect(s));
        if (!s.isConsole()) row.setOnLongClickListener(v -> { host.onRename(s); return true; });
        LayoutParams rp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 54));
        rp.bottomMargin = Ui.dp(c, 2);
        row.setLayoutParams(rp);

        TextView dot = new TextView(c);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dot.setTextColor(s.isConsole() ? Ui.HINT : s.isAlive() ? Ui.ACCENT : Ui.DIM);
        dot.setPadding(0, 0, Ui.dp(c, 10), 0);
        row.addView(dot);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        TextView name = new TextView(c);
        name.setText(s.title());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(active ? Ui.TEXT : s.isEnded() ? Ui.DIM : Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
        texts.addView(name);
        TextView sub = new TextView(c);
        sub.setText(state(s));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Ui.DIM);
        sub.setSingleLine(true);
        texts.addView(sub);
        row.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!s.isConsole()) {
            TextView rename = Ui.iconButton(c, "✎");
            rename.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            rename.setTextColor(Ui.DIM);
            rename.setOnClickListener(v -> host.onRename(s));
            row.addView(rename);
            TextView close = Ui.iconButton(c, "×");
            close.setTextColor(Ui.DIM);
            close.setOnClickListener(v -> host.onClose(s));
            row.addView(close);
        }
        return row;
    }

    /** One-line state under a session's name. */
    static String state(KaliSession s) {
        if (s.isConsole()) return "Install and import log";
        if (s.isAlive()) return "Running";
        if (s.isEnded()) return "Ended";
        return "Starting…";
    }
}
