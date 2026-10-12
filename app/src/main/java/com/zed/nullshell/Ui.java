package com.zed.nullshell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Shared look: one dark palette, one accent, flat rounded surfaces. */
final class Ui {
    private Ui() { }

    // Not final on purpose: Theme.load() swaps the whole palette, and every screen reads these when it builds its views.
    static int BG = 0xFF0E1013, SURFACE = 0xFF171A1F, SURFACE2 = 0xFF21262D, LINE = 0xFF262B33;
    static int TEXT = 0xFFE6E8EB, DIM = 0xFF8B929C, ACCENT = 0xFF3DD6B0, ON_ACCENT = 0xFF06201A, DANGER = 0xFFE5534B;
    static int HINT = 0xFF5A616B, TRACK_OFF = 0xFF3A4048, THUMB_OFF = 0xFFB0B6BE, RIPPLE = 0x33FFFFFF, SCRIM = 0x99000000;
    static boolean light;
    static float radiusScale = 1f;

    static void install(Theme t, int accentOverride, float radius) {
        BG = t.bg; SURFACE = t.surface; SURFACE2 = t.surface2; LINE = t.line;
        TEXT = t.text; DIM = t.dim; DANGER = t.danger; light = t.light;
        ACCENT = accentOverride != 0 ? accentOverride : t.accent;
        ON_ACCENT = accentOverride != 0 ? Theme.onColor(accentOverride) : t.onAccent;
        HINT = Theme.mix(t.dim, t.bg, 0.45f);
        TRACK_OFF = Theme.mix(t.surface2, t.dim, 0.35f);
        THUMB_OFF = Theme.mix(t.dim, t.text, 0.45f);
        RIPPLE = Theme.withAlpha(t.light ? 0x000000 : 0xFFFFFF, t.light ? 0x1F : 0x33);
        SCRIM = t.light ? 0x66000000 : 0x99000000;
        radiusScale = radius;
    }

    /** Recolours views inflated from XML (their colours were written for the original dark palette). */
    static void recolor(View v) {
        Drawable bg = v.getBackground();
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
            if (c == 0xFF0E1013) v.setBackgroundColor(BG);
            else if (c == 0xFF171A1F) v.setBackgroundColor(SURFACE);
            else if (c == 0xFF262B33) v.setBackgroundColor(LINE);
            else if (c == 0x99000000) v.setBackgroundColor(SCRIM);
        }
        if (v instanceof TextView && ((TextView) v).getCurrentTextColor() == 0xFF8B929C) ((TextView) v).setTextColor(DIM);
        if (v instanceof android.widget.ProgressBar) {
            ((android.widget.ProgressBar) v).setProgressTintList(ColorStateList.valueOf(ACCENT));
            ((android.widget.ProgressBar) v).setProgressBackgroundTintList(ColorStateList.valueOf(SURFACE2));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolor(g.getChildAt(i));
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp * radiusScale));
        return d;
    }

    static Drawable ripple(Context c, int fill, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), fill == 0 ? null : rounded(c, fill, radiusDp),
                rounded(c, 0xFFFFFFFF, radiusDp));
    }

    private static TextView base(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(false);
        return t;
    }

    /** Square 44dp tap target with a text glyph ("+", "⋮"). */
    static TextView iconButton(Context c, String glyph) {
        TextView t = base(c, glyph, 22, TEXT);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        t.setBackground(ripple(c, 0, 22));
        return t;
    }

    /** Compact key for the extra-keys row. */
    static TextView key(Context c, String label) {
        TextView t = base(c, label, 12, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMinWidth(dp(c, 42));
        t.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 36));
        lp.setMargins(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        t.setLayoutParams(lp);
        setKeyActive(c, t, false);
        return t;
    }

    static void setKeyActive(Context c, TextView t, boolean active) {
        t.setBackground(ripple(c, active ? ACCENT : SURFACE2, 8));
        t.setTextColor(active ? ON_ACCENT : TEXT);
    }

    static final int PRIMARY = 0, SECONDARY = 1, DANGER_STYLE = 2;

    /** Full-width 44dp button: filled accent, quiet surface, or red text on surface. */
    static TextView button(Context c, String label, int style) {
        int fill = style == PRIMARY ? ACCENT : SURFACE2;
        int text = style == PRIMARY ? ON_ACCENT : style == DANGER_STYLE ? DANGER : TEXT;
        TextView t = base(c, label, 14, text);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setMinHeight(dp(c, 44));
        t.setBackground(ripple(c, fill, 10));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    static void tint(Switch sw) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, THUMB_OFF}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Theme.withAlpha(ACCENT, 0x80), TRACK_OFF}));
    }

    static void tint(SeekBar s) {
        s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(TRACK_OFF));
    }

    static void tint(EditText e) {
        int[][] states = {{android.R.attr.state_focused}, {}};
        e.setBackgroundTintList(new ColorStateList(states, new int[]{ACCENT, TRACK_OFF}));
    }

    /** Applies system-bar (and, when edge-to-edge, keyboard) insets as padding. No-op on API < 30. */
    static void applyInsets(View root) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    interface TextCallback { void onText(String text); }

    /** Small dialog asking for one line of text. */
    static void prompt(Context c, String title, String hint, String initial, String positive, TextCallback cb) {
        final EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHint(hint);
        input.setHintTextColor(HINT);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.getText().length());
        tint(input);
        LinearLayout box = new LinearLayout(c);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new Dlg(c).setTitle(title).setView(box)
                .setPositiveButton(positive, (d, w) -> cb.onText(input.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }
}
