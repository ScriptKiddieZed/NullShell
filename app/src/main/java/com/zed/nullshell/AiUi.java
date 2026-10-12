package com.zed.nullshell;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers shared by the AI screens. */
final class AiUi {
    private AiUi() { }

    static int dp(Context c, float v) { return Ui.dp(c, v); }

    static void copy(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    /** Vertical page with a top bar (back arrow + title). Returns the root; the bar is its first child. */
    static LinearLayout page(Activity a, String title, LinearLayout[] barOut) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 6), 0, dp(a, 6), 0);
        TextView back = Ui.iconButton(a, "←");
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);
        if (title != null) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setPadding(dp(a, 8), 0, 0, 0);
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52)));
        View line = new View(a);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (barOut != null && barOut.length > 0) barOut[0] = bar;
        return root;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.rounded(c, Ui.SURFACE, 14));
        l.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        l.setLayoutParams(lp);
        return l;
    }

    static TextView heading(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase());
        t.setTextColor(Ui.ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 14), 0, dp(c, 6));
        return t;
    }

    static TextView note(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 6));
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(c, 8), 0, dp(c, 2));
        return t;
    }

    static EditText field(Context c, String hint, String value, int lines) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Ui.HINT);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        e.setText(value == null ? "" : value);
        if (lines > 1) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(lines);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        Ui.tint(e);
        return e;
    }

    static void toast(Context c, String s) { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show(); }
}
