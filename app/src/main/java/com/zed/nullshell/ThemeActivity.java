package com.zed.nullshell;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Appearance: pick a theme from live previews, an accent colour, how round the corners are, and automatic day / night. */
public class ThemeActivity extends Activity {
    private static int savedScroll;
    private ScrollView scroll;
    private LinearLayout content;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);
        TextView back = Ui.iconButton(this, "←");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("Appearance");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.applyInsets(root);
        build();
        final int y = savedScroll;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(4), dp(8), dp(4), 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        return c;
    }

    /** Re-reads the saved options and rebuilds every screen. */
    private void changed() {
        savedScroll = scroll.getScrollY();
        if (AppIcon.follow(this) && !Theme.auto(this)) AppIcon.apply(this, Theme.chosenId(this));
        recreate();
    }

    // ------------------------------------------------------------------ screen

    private void build() {
        content.removeAllViews();
        final boolean auto = Theme.auto(this);

        // automatic day / night
        LinearLayout autoCard = card();
        Switch sw = new Switch(this);
        sw.setText("Follow the system dark mode");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setPadding(0, dp(6), 0, dp(6));
        Ui.tint(sw);
        sw.setChecked(auto);
        sw.setOnCheckedChangeListener((v, on) -> { saveAuto(on); changed(); });
        autoCard.addView(sw);
        if (auto) {
            autoCard.addView(themeRow("Light theme", Theme.dayId(this), true));
            autoCard.addView(themeRow("Dark theme", Theme.nightId(this), false));
        }
        content.addView(autoCard);

        if (!auto) {
            content.addView(heading("Theme"));
            addGrid(Theme.ALL);
        } else {
            content.addView(note("Pick the two themes above. The app switches when the phone does."));
        }

        // accent
        content.addView(heading("Accent colour"));
        content.addView(accentRow());
        content.addView(note("Used for buttons, switches, the active tab and highlights. “Theme” keeps each theme's own accent."));

        // corners
        content.addView(heading("Corners"));
        content.addView(radiusRow());

        // launcher icon
        content.addView(heading("App icon"));
        content.addView(iconSection());

        content.addView(heading("Terminal and editor"));
        LinearLayout rel = card();
        TextView t = new TextView(this);
        t.setText("Terminal colours and the code editor theme follow the app theme by default. "
                + "Change them separately under Settings › Terminal and Settings › Editor.");
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        rel.addView(t);
        content.addView(rel);
        content.addView(note("Colours already printed in a terminal tab keep the palette they were drawn with; new output uses the new one."));
    }

    private void saveAuto(boolean on) {
        Theme.save(this, Theme.chosenId(this), on, Theme.dayId(this), Theme.nightId(this), Theme.accent(this), Theme.radiusIndex(this));
    }

    private View themeRow(String label, final String currentId, final boolean day) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.TEXT);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = Ui.button(this, Theme.byId(currentId).name, Ui.SECONDARY);
        pick.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pick.setOnClickListener(v -> {
            final String[] names = new String[Theme.ALL.size()];
            int checked = 0;
            for (int i = 0; i < names.length; i++) {
                names[i] = Theme.ALL.get(i).name + (Theme.ALL.get(i).light ? "  (light)" : "");
                if (Theme.ALL.get(i).id.equals(currentId)) checked = i;
            }
            new Dlg(this).setTitle(label).setSingleChoiceItems(names, checked, (d, which) -> {
                String id = Theme.ALL.get(which).id;
                Theme.save(this, Theme.chosenId(this), true, day ? id : Theme.dayId(this), day ? Theme.nightId(this) : id,
                        Theme.accent(this), Theme.radiusIndex(this));
                d.dismiss();
                changed();
            }).show();
        });
        row.addView(pick);
        return row;
    }

    private void addGrid(java.util.List<Theme> themes) {
        final String selected = Theme.chosenId(this);
        LinearLayout rowL = null;
        for (int i = 0; i < themes.size(); i++) {
            if (i % 2 == 0) {
                rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(rowL, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final Theme t = themes.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(4), dp(4), dp(4), dp(8));
            ThemePreview pv = new ThemePreview(this, t, Theme.accent(this), t.id.equals(selected));
            cell.addView(pv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));
            TextView name = new TextView(this);
            name.setText((t.id.equals(selected) ? "✓  " : "") + t.name);
            name.setTextColor(t.id.equals(selected) ? Ui.ACCENT : Ui.TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setPadding(dp(4), dp(6), 0, 0);
            cell.addView(name);
            cell.setOnClickListener(v -> {
                Theme.save(this, t.id, false, Theme.dayId(this), Theme.nightId(this), Theme.accent(this), Theme.radiusIndex(this));
                changed();
            });
            rowL.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (themes.size() % 2 == 1 && rowL != null) rowL.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    private View accentRow() {
        final int current = Theme.accent(this);
        LinearLayout wrap = card();
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(swatch(0, current == 0, "Theme"));
        boolean found = current == 0;
        for (int a : Theme.ACCENTS) {
            row.addView(swatch(a, a == current, null));
            if (a == current) found = true;
        }
        if (!found) row.addView(swatch(current, true, null));
        TextView custom = Ui.key(this, "Custom…");
        custom.setOnClickListener(v -> Ui.prompt(this, "Custom accent", "RRGGBB", current == 0 ? "" : String.format("%06X", current & 0xFFFFFF), "Apply", s -> {
            int c = Theme.parseHex(s);
            if (c == 0) { android.widget.Toast.makeText(this, "Enter six hex digits, like 3DD6B0", android.widget.Toast.LENGTH_SHORT).show(); return; }
            setAccent(c);
        }));
        row.addView(custom);
        hs.addView(row);
        wrap.addView(hs);
        return wrap;
    }

    private void setAccent(int c) {
        Theme.save(this, Theme.chosenId(this), Theme.auto(this), Theme.dayId(this), Theme.nightId(this), c, Theme.radiusIndex(this));
        changed();
    }

    /** A round colour chip; 0 means "the theme's own accent". */
    private View swatch(final int color, boolean selected, String label) {
        int shown = color == 0 ? Theme.current.accent : color;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(5), dp(2), dp(5), dp(2));
        View v = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(shown);
        d.setStroke(selected ? dp(3) : dp(1), selected ? Ui.TEXT : Ui.LINE);
        v.setBackground(d);
        box.addView(v, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView t = new TextView(this);
        t.setText(label == null ? " " : label);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        box.addView(t);
        box.setOnClickListener(x -> setAccent(color));
        return box;
    }

    private View iconSection() {
        LinearLayout wrap = card();
        Switch sw = new Switch(this);
        sw.setText("Match the app theme");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setPadding(0, dp(6), 0, dp(6));
        Ui.tint(sw);
        sw.setChecked(AppIcon.follow(this));
        sw.setOnCheckedChangeListener((v, on) -> {
            AppIcon.setFollow(this, on);
            if (on && !Theme.auto(this)) AppIcon.apply(this, Theme.chosenId(this));
            savedScroll = scroll.getScrollY();
            build();
            scroll.post(() -> scroll.scrollTo(0, savedScroll));
        });
        wrap.addView(sw);
        final String cur = AppIcon.current(this);
        final int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < Theme.ALL.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                wrap.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final Theme t = Theme.ALL.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2), dp(8), dp(2), dp(6));
            boolean sel = t.id.equals(cur);
            cell.setBackground(sel ? Ui.rounded(this, Ui.SURFACE2, 12) : null);

            final int box = dp(56);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.setClipToOutline(true);
            frame.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.3f); }
            });
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setImageDrawable(AppIcon.preview(this, t.id));
            int big = box * 3 / 2;   // an adaptive icon draws 108dp but only the middle 72dp is visible
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(big, big, Gravity.CENTER);
            frame.addView(img, lp);
            cell.addView(frame, new LinearLayout.LayoutParams(box, box));

            TextView name = new TextView(this);
            name.setText(t.name);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setTextColor(sel ? Ui.ACCENT : Ui.DIM);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            name.setPadding(0, dp(4), 0, 0);
            cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(x -> {
                AppIcon.setFollow(this, false);
                AppIcon.apply(this, t.id);
                android.widget.Toast.makeText(this, "Icon set to " + t.name + ". Your launcher may take a few seconds to update.", android.widget.Toast.LENGTH_LONG).show();
                savedScroll = scroll.getScrollY();
                build();
                scroll.post(() -> scroll.scrollTo(0, savedScroll));
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int rem = Theme.ALL.size() % cols;
        if (rem != 0 && row != null) for (int i = rem; i < cols; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        return wrap;
    }

    private View radiusRow() {
        LinearLayout wrap = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int cur = Theme.radiusIndex(this);
        for (int i = 0; i < Theme.RADIUS_NAMES.length; i++) {
            final int idx = i;
            TextView t = Ui.key(this, Theme.RADIUS_NAMES[i]);
            Ui.setKeyActive(this, t, i == cur);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
            t.setOnClickListener(v -> {
                Theme.save(this, Theme.chosenId(this), Theme.auto(this), Theme.dayId(this), Theme.nightId(this), Theme.accent(this), idx);
                changed();
            });
            row.addView(t);
        }
        wrap.addView(row);
        return wrap;
    }

    // ------------------------------------------------------------------ preview card

    /** A tiny mock of the app in a theme: tabs, a few terminal lines in the theme's colours, the extra-keys row. */
    private static final class ThemePreview extends View {
        private final Theme t;
        private final int accent;
        private final boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        ThemePreview(Context c, Theme t, int accentOverride, boolean selected) {
            super(c);
            this.t = t;
            this.accent = accentOverride != 0 ? accentOverride : t.accent;
            this.selected = selected;
            p.setTypeface(Typeface.MONOSPACE);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float rad = Ui.dp(getContext(), 12) * Math.min(1.2f, Ui.radiusScale);
            float s = Ui.dp(getContext(), 1);
            int[] ansi = t.ansi != null ? t.ansi : Theme.CLASSIC;

            p.setStyle(Paint.Style.FILL);
            p.setColor(t.bg);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, p);

            // tab bar
            p.setColor(t.surface);
            r.set(0, 0, w, 20 * s);
            c.save();
            c.clipRect(0, 0, w, 20 * s);
            c.drawRoundRect(new RectF(0, 0, w, 40 * s), rad, rad, p);
            c.restore();
            p.setColor(t.surface2);
            r.set(8 * s, 5 * s, 44 * s, 16 * s);
            c.drawRoundRect(r, 4 * s, 4 * s, p);
            p.setColor(accent);
            r.set(12 * s, 17 * s, 40 * s, 19 * s);
            c.drawRoundRect(r, 1 * s, 1 * s, p);
            p.setColor(t.dim);
            r.set(52 * s, 8 * s, 78 * s, 13 * s);
            c.drawRoundRect(r, 2 * s, 2 * s, p);

            // terminal
            p.setColor(t.termBg);
            c.drawRect(0, 20 * s, w, h - 24 * s, p);
            p.setTextSize(9 * s);
            float x = 8 * s, y = 33 * s;
            p.setColor(ansi[2]);
            c.drawText("root@kali", x, y, p);
            p.setColor(t.termFg);
            c.drawText(" ~ # nmap", x + p.measureText("root@kali"), y, p);
            y += 12 * s;
            p.setColor(ansi[4]);
            c.drawText("22/tcp", x, y, p);
            p.setColor(ansi[2]);
            c.drawText(" open", x + p.measureText("22/tcp"), y, p);
            y += 12 * s;
            p.setColor(ansi[1]);
            c.drawText("80/tcp", x, y, p);
            p.setColor(ansi[3]);
            c.drawText(" filtered", x + p.measureText("80/tcp"), y, p);
            y += 12 * s;
            p.setColor(t.termFg);
            c.drawText("$ ", x, y, p);
            p.setColor(accent);
            r.set(x + p.measureText("$ "), y - 8 * s, x + p.measureText("$ ") + 5 * s, y + 1 * s);
            c.drawRect(r, p);

            // extra keys
            p.setColor(t.line);
            c.drawRect(0, h - 24 * s, w, h - 24 * s + 1, p);
            p.setColor(t.bg);
            c.drawRect(0, h - 23 * s, w, h, p);
            float kx = 6 * s;
            for (int i = 0; i < 5; i++) {
                p.setColor(i == 0 ? accent : t.surface2);
                r.set(kx, h - 19 * s, kx + 22 * s, h - 6 * s);
                c.drawRoundRect(r, 4 * s, 4 * s, p);
                kx += 26 * s;
            }

            // border (thick accent when selected)
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(selected ? 3 * s : 1 * s);
            p.setColor(selected ? accent : t.line);
            float in = p.getStrokeWidth() / 2;
            r.set(in, in, w - in, h - in);
            c.drawRoundRect(r, rad, rad, p);
        }
    }
}
