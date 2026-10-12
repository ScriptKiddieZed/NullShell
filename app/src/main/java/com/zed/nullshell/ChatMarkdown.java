package com.zed.nullshell;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ArrowKeyMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.content.res.ColorStateList;
import android.widget.ImageView;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Draws a chat reply as views: text with light Markdown, code cards with a Copy button, and tables. */
final class ChatMarkdown {
    private ChatMarkdown() { }

    // ------------------------------------------------------------------ the message body

    /** A column of blocks that can be refreshed in place while a reply streams in. */
    interface CodeAction { void run(String code); }

    /** Languages whose snippets can be pasted into the terminal. An unlabelled block counts too. */
    static boolean isShell(String lang) {
        String l = lang == null ? "" : lang.toLowerCase(java.util.Locale.ROOT);
        return l.isEmpty() || l.equals("bash") || l.equals("sh") || l.equals("shell") || l.equals("zsh") || l.equals("console")
                || l.equals("terminal") || l.equals("shell-session") || l.equals("shellsession") || l.equals("fish");
    }

    /** The code ready for the command line: a leading "$ " prompt on every line is dropped. */
    static String forTerminal(String code) {
        String[] lines = code.split("\n", -1);
        boolean all = true, any = false;
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            any = true;
            if (!l.startsWith("$ ")) { all = false; break; }
        }
        if (!any || !all) return code;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].startsWith("$ ") ? lines[i].substring(2) : lines[i]);
        }
        return sb.toString();
    }

    static final class Body extends LinearLayout {
        private CodeAction terminal;

        /** When set, shell snippets get a "paste into terminal" button. */
        void setTerminalAction(CodeAction a) { terminal = a; }

        Body(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        void setMarkdown(String md) {
            List<MdBlocks.Block> blocks = MdBlocks.split(md);
            int n = blocks.size();
            for (int i = 0; i < n; i++) {
                MdBlocks.Block b = blocks.get(i);
                View existing = i < getChildCount() ? getChildAt(i) : null;
                View fresh = null;
                if (b.kind == MdBlocks.CODE) {
                    if (existing instanceof CodeCard) ((CodeCard) existing).update(b.lang, b.text);
                    else fresh = new CodeCard(getContext(), b.lang, b.text, terminal);
                } else if (b.kind == MdBlocks.TABLE) {
                    if (existing instanceof TableCard) ((TableCard) existing).update(b.text);
                    else fresh = new TableCard(getContext(), b.text);
                } else {
                    if (existing instanceof TextBlock) ((TextBlock) existing).update(b.text);
                    else fresh = new TextBlock(getContext(), b.text);
                }
                if (fresh != null) {
                    LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = i == 0 ? 0 : Ui.dp(getContext(), 6);
                    if (existing != null) removeViewAt(i);
                    addView(fresh, i, lp);
                }
            }
            while (getChildCount() > n) removeViewAt(getChildCount() - 1);
        }
    }

    // ------------------------------------------------------------------ text

    static final class TextBlock extends TextView {
        private String src = "";
        TextBlock(Context c, String md) {
            super(c);
            setTextColor(Ui.TEXT);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            setLineSpacing(0, 1.12f);
            setLinkTextColor(Ui.ACCENT);
            setTextIsSelectable(true);                       // long-press selects words; handles + copy come from the system
            setMovementMethod(SelectableLinks.INSTANCE);     // ...while links stay tappable
            update(md);
        }
        void update(String md) {
            if (md.equals(src)) return;
            src = md;
            setText(render(getContext(), md));
        }
    }

    /** Selection (long-press) and tappable links together: a plain LinkMovementMethod would turn selection off. */
    static final class SelectableLinks extends ArrowKeyMovementMethod {
        static final SelectableLinks INSTANCE = new SelectableLinks();
        @Override public boolean onTouchEvent(TextView w, Spannable buf, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP && w.getSelectionStart() == w.getSelectionEnd()) {
                int x = (int) e.getX() - w.getTotalPaddingLeft() + w.getScrollX();
                int y = (int) e.getY() - w.getTotalPaddingTop() + w.getScrollY();
                Layout l = w.getLayout();
                if (l != null) {
                    int line = l.getLineForVertical(y);
                    if (x >= l.getLineLeft(line) && x <= l.getLineRight(line)) {
                        int off = l.getOffsetForHorizontal(line, x);
                        ClickableSpan[] links = buf.getSpans(off, off, ClickableSpan.class);
                        if (links.length > 0) { links[0].onClick(w); return true; }
                    }
                }
            }
            return super.onTouchEvent(w, buf, e);
        }
    }

    /** A 38dp tinted icon button. */
    static ImageView iconAction(Context c, int res, String description, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(Ui.DIM));
        v.setContentDescription(description);
        int p = Ui.dp(c, 9);
        v.setPadding(p, p, p, p);
        v.setBackground(Ui.ripple(c, 0, 19));
        v.setClickable(true);
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)));
        return v;
    }

    /** Swaps an icon for a check mark for a moment ("copied"). */
    static void flashCheck(final ImageView v, final int original) {
        v.setImageResource(R.drawable.ic_act_check);
        v.setImageTintList(ColorStateList.valueOf(Ui.ACCENT));
        v.postDelayed(() -> { v.setImageResource(original); v.setImageTintList(ColorStateList.valueOf(Ui.DIM)); }, 1400);
    }

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    static CharSequence render(Context c, String md) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = md.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (li > 0) sb.append('\n');
            int start = sb.length();
            Matcher m;
            if ((m = HEADING.matcher(line)).matches()) {
                int level = m.group(1).length();
                inline(c, sb, m.group(2));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                float[] size = {1.35f, 1.22f, 1.12f, 1.05f, 1.0f, 1.0f};
                sb.setSpan(new RelativeSizeSpan(size[level - 1]), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (RULE.matcher(line).matches()) {
                sb.append("────────────");
                sb.setSpan(new ForegroundColorSpan(Ui.DIM), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((m = BULLET.matcher(line)).matches()) {
                int indent = Ui.dp(c, 4 + 14 * (m.group(1).length() / 2));
                sb.append("•  ");
                inline(c, sb, m.group(2));
                sb.setSpan(new LeadingMarginSpan.Standard(indent, indent + Ui.dp(c, 16)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                int indent = Ui.dp(c, 4 + 14 * (m.group(1).length() / 2));
                sb.append(m.group(2)).append(".  ");
                inline(c, sb, m.group(3));
                sb.setSpan(new LeadingMarginSpan.Standard(indent, indent + Ui.dp(c, 20)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((m = QUOTE.matcher(line)).matches()) {
                sb.append("▍ ");
                inline(c, sb, m.group(1));
                sb.setSpan(new ForegroundColorSpan(Ui.DIM), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(c, sb, line);
            }
        }
        return sb;
    }

    /** `code`, **bold**, *italic*, ~~strike~~ and [text](url). Anything unmatched stays as typed. */
    private static void inline(Context c, SpannableStringBuilder out, String s) {
        int i = 0, n = s.length();
        StringBuilder lit = new StringBuilder();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!~|>".indexOf(s.charAt(i + 1)) >= 0) {
                lit.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == '`') {
                int e = s.indexOf('`', i + 1);
                if (e > i + 1) {
                    flush(out, lit);
                    int a = out.length();
                    out.append(s, i + 1, e);
                    out.setSpan(new TypefaceSpan("monospace"), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(Theme.mix(Ui.SURFACE2, Ui.TEXT, 0.06f)), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.93f), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 1;
                    continue;
                }
            } else if (ch == '*' && s.startsWith("**", i)) {
                int e = s.indexOf("**", i + 2);
                if (e > i + 2) {
                    flush(out, lit);
                    int a = out.length();
                    inline(c, out, s.substring(i + 2, e));
                    out.setSpan(new StyleSpan(Typeface.BOLD), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 2;
                    continue;
                }
            } else if (ch == '~' && s.startsWith("~~", i)) {
                int e = s.indexOf("~~", i + 2);
                if (e > i + 2) {
                    flush(out, lit);
                    int a = out.length();
                    inline(c, out, s.substring(i + 2, e));
                    out.setSpan(new StrikethroughSpan(), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 2;
                    continue;
                }
            } else if (ch == '*' && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1)) && s.charAt(i + 1) != '*') {
                int e = s.indexOf('*', i + 1);
                while (e > 0 && e + 1 < n && s.charAt(e + 1) == '*') e = s.indexOf('*', e + 2);
                if (e > i + 1 && !Character.isWhitespace(s.charAt(e - 1))) {
                    flush(out, lit);
                    int a = out.length();
                    inline(c, out, s.substring(i + 1, e));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 1;
                    continue;
                }
            } else if (ch == '[') {
                int close = s.indexOf("](", i + 1);
                int end = close < 0 ? -1 : s.indexOf(')', close + 2);
                if (close > i && end > close) {
                    String url = s.substring(close + 2, end).trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        flush(out, lit);
                        int a = out.length();
                        inline(c, out, s.substring(i + 1, close));
                        out.setSpan(new URLSpan(url), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = end + 1;
                        continue;
                    }
                }
            }
            lit.append(ch);
            i++;
        }
        flush(out, lit);
    }

    private static void flush(SpannableStringBuilder out, StringBuilder lit) {
        if (lit.length() > 0) { out.append(lit); lit.setLength(0); }
    }

    // ------------------------------------------------------------------ code card

    static final class CodeCard extends LinearLayout {
        private final TextView langView, bodyView;
        private final ImageView termBtn;
        private String code = "";
        private boolean terminalCapable;

        CodeCard(Context c, String lang, String code, final CodeAction terminal) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(Ui.rounded(c, Theme.mix(Ui.BG, Ui.SURFACE2, 0.35f), 10));

            langView = new TextView(c);
            langView.setTextColor(Ui.DIM);
            langView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            langView.setTypeface(Typeface.MONOSPACE);
            langView.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), 0);
            addView(langView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(OVER_SCROLL_NEVER);
            bodyView = new TextView(c);
            bodyView.setTypeface(Typeface.MONOSPACE);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            bodyView.setTextColor(Ui.TEXT);
            bodyView.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 2));
            bodyView.setHorizontallyScrolling(true);
            bodyView.setTextIsSelectable(true);
            hs.addView(bodyView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(hs, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // actions at the end of the snippet
            LinearLayout foot = new LinearLayout(c);
            foot.setOrientation(HORIZONTAL);
            foot.setGravity(Gravity.END);
            foot.setPadding(0, 0, Ui.dp(c, 4), Ui.dp(c, 2));
            final ImageView copy = iconAction(c, R.drawable.ic_act_copy, "Copy code", null);
            copy.setOnClickListener(v -> { AiUi.copy(getContext(), "code", this.code); flashCheck(copy, R.drawable.ic_act_copy); });
            termBtn = iconAction(c, R.drawable.ic_act_terminal, "Paste into terminal", v -> { if (terminal != null) terminal.run(forTerminal(this.code)); });
            foot.addView(termBtn);
            foot.addView(copy);
            terminalCapable = terminal != null;
            addView(foot, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            update(lang, code);
        }

        void update(String lang, String code) {
            this.code = code;
            termBtn.setVisibility(terminalCapable && isShell(lang) ? View.VISIBLE : View.GONE);
            langView.setText(lang == null || lang.isEmpty() ? "code" : lang);
            if (!code.contentEquals(bodyView.getText())) bodyView.setText(code);
        }
    }

    // ------------------------------------------------------------------ table card

    static final class TableCard extends HorizontalScrollView {
        private final TextView tv;
        private String src = "";
        TableCard(Context c, String raw) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setBackground(Ui.rounded(c, Theme.mix(Ui.BG, Ui.SURFACE2, 0.35f), 10));
            tv = new TextView(c);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tv.setTextColor(Ui.TEXT);
            tv.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
            tv.setHorizontallyScrolling(true);
            tv.setTextIsSelectable(true);
            addView(tv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            update(raw);
        }
        void update(String raw) {
            if (raw.equals(src)) return;
            src = raw;
            tv.setText(MdBlocks.tableText(raw));
        }
    }
}
