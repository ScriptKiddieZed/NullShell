package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Browsing history with search. Returns the chosen URL (and whether to open it in a new tab) to BrowserActivity. */
public class BrowserHistoryActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_NEW_TAB = "new_tab";

    private LinearLayout list;
    private TextView empty;
    private EditText search;
    private final DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        TextView back = Ui.iconButton(this, "\u2190");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("History");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(this);
        clear.setText("Clear all");
        clear.setTextColor(Ui.DANGER);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        clear.setBackground(Ui.ripple(this, 0, 10));
        clear.setOnClickListener(v -> new Dlg(this).setTitle("Clear history?")
                .setMessage("All visited pages are removed. Cookies and site data stay.")
                .setPositiveButton("Clear", (d, w) -> { BrowserHistory.get(this).clear(); list.removeAllViews(); showEmpty(true); })
                .setNegativeButton("Cancel", null).show());
        bar.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search history");
        search.setHintTextColor(Ui.HINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setBackground(Ui.rounded(this, Ui.SURFACE2, 10));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable e) { reload(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        sp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 8));
        root.addView(search, sp);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("Nothing here yet");
        empty.setTextColor(Ui.DIM);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 40), 0, 0);
        empty.setVisibility(View.GONE);
        root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        Ui.applyInsets(root);
        reload();
    }

    private void showEmpty(boolean on) { empty.setVisibility(on ? View.VISIBLE : View.GONE); }

    private void reload() {
        list.removeAllViews();
        List<BrowserHistory.Item> items = BrowserHistory.get(this).search(search.getText().toString(), 300);
        showEmpty(items.isEmpty());
        for (BrowserHistory.Item it : items) list.addView(row(it));
    }

    private View row(final BrowserHistory.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        row.setClickable(true);
        row.setBackground(Ui.ripple(this, 0, 0));

        TextView t = new TextView(this);
        t.setText(it.title.isEmpty() ? it.url : it.title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);

        TextView u = new TextView(this);
        u.setText(it.url);
        u.setTextColor(Ui.ACCENT);
        u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        u.setSingleLine(true);
        u.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(u);

        TextView meta = new TextView(this);
        meta.setText(fmt.format(new Date(it.last)) + (it.visits > 1 ? "  \u00B7  " + it.visits + " visits" : ""));
        meta.setTextColor(Ui.DIM);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        row.addView(meta);

        row.setOnClickListener(v -> choose(it.url, false));
        row.setOnLongClickListener(v -> {
            new Dlg(this).setTitle(it.title.isEmpty() ? it.url : it.title)
                    .setItems(new String[]{"Open in new tab", "Copy link", "Delete"}, (d, which) -> {
                        if (which == 0) choose(it.url, true);
                        else if (which == 1) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", it.url));
                        } else {
                            BrowserHistory.get(this).delete(it.id);
                            list.removeView(row);
                            if (list.getChildCount() == 0) showEmpty(true);
                        }
                    }).show();
            return true;
        });
        return row;
    }

    private void choose(String url, boolean newTab) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_URL, url).putExtra(EXTRA_NEW_TAB, newTab));
        finish();
    }
}
