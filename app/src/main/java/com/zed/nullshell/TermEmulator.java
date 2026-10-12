package com.zed.nullshell;

import java.nio.charset.StandardCharsets;

/**
 * Small VT100/xterm-compatible terminal emulator. Pure Java on purpose (no Android
 * imports) so it can be unit-tested on a desktop JVM.
 *
 * Thread model: process()/append()/resize() run on the output thread or UI thread;
 * the renderer must hold {@code synchronized (emulator)} while reading cells.
 */
public final class TermEmulator {
    public interface Responder { void write(byte[] data); }

    public static final int AT_BOLD = 1, AT_DIM = 2, AT_ITALIC = 4, AT_UNDER = 8,
            AT_INVERSE = 16, AT_STRIKE = 32, AT_WIDE_CONT = 64, AT_INVIS = 128;

    public static final class Row {
        public int[] ch, fg, bg, at;
        /** True when this row soft-wrapped into the next one (used for reflow and copy). */
        public boolean wrapped;
        Row(int n, int bgColor) {
            ch = new int[n]; fg = new int[n]; bg = new int[n]; at = new int[n];
            if (bgColor != 0) java.util.Arrays.fill(bg, bgColor);
        }
        Row resized(int n, int bgColor) {
            Row r = new Row(n, bgColor);
            int m = Math.min(n, ch.length);
            System.arraycopy(ch, 0, r.ch, 0, m);
            System.arraycopy(fg, 0, r.fg, 0, m);
            System.arraycopy(bg, 0, r.bg, 0, m);
            System.arraycopy(at, 0, r.at, 0, m);
            r.wrapped = wrapped;
            return r;
        }
    }

    // ---- palette (0 means "default colour" everywhere else) ----
    private static final int[] PAL = new int[256];

    /** Replaces the 16 standard colours (for text printed from now on). */
    public static void setAnsiPalette(int[] argb16) {
        if (argb16 != null && argb16.length >= 16) System.arraycopy(argb16, 0, PAL, 0, 16);
    }
    static {
        int[] base = {
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF };
        for (int i = 0; i < 16; i++) PAL[i] = 0xFF000000 | base[i];
        int[] lv = {0, 95, 135, 175, 215, 255};
        for (int i = 0; i < 216; i++)
            PAL[16 + i] = 0xFF000000 | (lv[i / 36] << 16) | (lv[(i / 6) % 6] << 8) | lv[i % 6];
        for (int i = 0; i < 24; i++) { int v = 8 + i * 10; PAL[232 + i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
    }

    private static final int[] GFX = {
            ' ', 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0xB0, 0xB1, 0x2424, 0x240B,
            0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD,
            0x251C, 0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x3C0, 0x2260, 0xA3, 0xB7 };

    private static volatile int defaultScrollback = 3000;

    /** History length for emulators created from now on. */
    public static void setDefaultScrollback(int lines) { defaultScrollback = Math.max(200, Math.min(20000, lines)); }

    private int cols, rows;
    private Row[] scr;
    private Row[] saved;            // the other screen (main while in alt, alt while in main)
    private boolean usingAlt;
    private final int maxSb = defaultScrollback;
    private final Row[] sb = new Row[maxSb];
    private int sbStart, sbCount;
    private long dropped;           // scrollback lines discarded so far; absolute row + dropped = a stable line id

    private int curX, curY, savedX, savedY, savedFg, savedBg, savedAt;
    private boolean wrapPending, autowrap = true, insertMode, cursorVisible = true;
    private boolean appCursorKeys, bracketedPaste;
    private int mouseMode;          // 0 off, else 1000 / 1002 / 1003
    private boolean mouseSgr;
    private int top, bottom;
    private int fg, bg, at;
    private boolean[] tabs;
    private int lastChar;
    private final int[] g = {0, 0};  // 0 = ASCII, 1 = DEC graphics
    private int gsel;
    private String title = "";
    private Responder responder;

    // parser
    private static final int S_GROUND = 0, S_ESC = 1, S_INTER = 2, S_CSI = 3, S_OSC = 4, S_OSC_ESC = 5, S_STR = 6, S_STR_ESC = 7;
    private int state = S_GROUND;
    private char interChar;
    private final int[] params = new int[32];
    private int pc;
    private boolean pHas;
    private int pCur;
    private int csiPriv;   // '?', '>', '<', '=' or 0
    private int csiInter;  // intermediate (e.g. ' ' in "CSI Ps SP q")
    private final StringBuilder osc = new StringBuilder();
    private int utfNeed, utfCp;

    public TermEmulator(int cols, int rows) {
        this.cols = Math.max(2, cols);
        this.rows = Math.max(2, rows);
        reset();
    }

    public void setResponder(Responder r) { responder = r; }

    // ------------------------------------------------------------------ public API

    public synchronized int getCols() { return cols; }
    public synchronized int getRows() { return rows; }
    public synchronized int getCursorX() { return curX; }
    public synchronized int getCursorY() { return curY; }
    public synchronized boolean isCursorVisible() { return cursorVisible; }
    public synchronized boolean isAppCursorKeys() { return appCursorKeys; }
    public synchronized boolean isBracketedPaste() { return bracketedPaste; }
    public synchronized int getMouseMode() { return mouseMode; }
    public synchronized boolean isMouseSgr() { return mouseSgr; }
    public synchronized String getTitle() { return title; }
    public synchronized int getScrollbackSize() { return sbCount; }
    /** Lines that fell off the top of the scrollback. {@code absRow index + this} never changes for a given line. */
    public synchronized long getDroppedLines() { return dropped; }
    public synchronized boolean isAltScreen() { return usingAlt; }

    /** Absolute row index: 0 = oldest scrollback line, scrollbackSize() = first screen row. */
    public synchronized Row absRow(int abs) {
        if (abs < sbCount) return sb[(sbStart + Math.max(0, abs)) % maxSb];
        int i = abs - sbCount;
        return scr[Math.min(i, rows - 1)];
    }

    public synchronized void append(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        process(b, 0, b.length);
    }

    public synchronized void process(byte[] b, int off, int len) {
        final int end = off + len;
        for (int i = off; i < end; i++) {
            int c = b[i] & 0xFF;
            // fast path: plain printable ASCII (most of what a terminal prints) goes straight into the row
            if (c >= 0x20 && c < 0x7F && state == S_GROUND && utfNeed == 0 && g[gsel] == 0 && !insertMode) {
                i = putAsciiRun(b, i, end) - 1;
                continue;
            }
            if (utfNeed > 0) {
                if ((c & 0xC0) == 0x80) {
                    utfCp = (utfCp << 6) | (c & 0x3F);
                    if (--utfNeed == 0) handle(utfCp);
                    continue;
                }
                utfNeed = 0;
                handle(0xFFFD); // broken sequence; fall through and treat c normally
            }
            if (c < 0x80) handle(c);
            else if ((c & 0xE0) == 0xC0) { utfNeed = 1; utfCp = c & 0x1F; }
            else if ((c & 0xF0) == 0xE0) { utfNeed = 2; utfCp = c & 0x0F; }
            else if ((c & 0xF8) == 0xF0) { utfNeed = 3; utfCp = c & 0x07; }
            else handle(0xFFFD);
        }
    }

    /** Writes the run of printable ASCII starting at b[i] and returns the index after it. Same result as put() per byte. */
    private int putAsciiRun(byte[] b, int i, int end) {
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c < 0x20 || c >= 0x7F) break;
            if (wrapPending) {
                if (autowrap) { scr[curY].wrapped = true; curX = 0; lineFeed(); }
                wrapPending = false;
            }
            Row r = scr[curY];
            int x = curX;
            int n = Math.min(end - i, cols - x);
            int[] ch = r.ch;
            int k = 0;
            for (; k < n; k++) {
                int v = b[i + k] & 0xFF;
                if (v < 0x20 || v >= 0x7F) break;
                ch[x + k] = v;
            }
            java.util.Arrays.fill(r.fg, x, x + k, fg);
            java.util.Arrays.fill(r.bg, x, x + k, bg);
            java.util.Arrays.fill(r.at, x, x + k, at);
            lastChar = ch[x + k - 1];
            i += k;
            x += k;
            if (x >= cols) { curX = cols - 1; wrapPending = true; } else curX = x;
        }
        return i;
    }

    public synchronized String getText(int r1, int c1, int r2, int c2) {
        if (r1 > r2 || (r1 == r2 && c1 > c2)) { int t = r1; r1 = r2; r2 = t; t = c1; c1 = c2; c2 = t; }
        int total = sbCount + rows;
        r1 = Math.max(0, r1); r2 = Math.min(total - 1, r2);
        StringBuilder out = new StringBuilder();
        for (int r = r1; r <= r2; r++) {
            Row row = absRow(r);
            int from = (r == r1) ? c1 : 0;
            int to = (r == r2) ? c2 : row.ch.length - 1;
            to = Math.min(to, row.ch.length - 1);
            StringBuilder line = new StringBuilder();
            for (int x = Math.max(0, from); x <= to; x++) {
                if ((row.at[x] & AT_WIDE_CONT) != 0) continue;
                line.appendCodePoint(row.ch[x] == 0 ? ' ' : row.ch[x]);
            }
            int e = line.length();
            if (!(row.wrapped && r != r2)) while (e > 0 && line.charAt(e - 1) == ' ') e--;
            out.append(line, 0, e);
            if (r != r2 && !row.wrapped) out.append('\n');
        }
        return out.toString();
    }

    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(2, newCols); newRows = Math.max(2, newRows);
        if (newCols == cols && newRows == rows) return;
        if (!usingAlt && newCols != cols) reflow(newCols, newRows);
        else plainResize(newCols, newRows);
    }

    private static boolean isBlankRow(Row r) {
        for (int i = 0; i < r.ch.length; i++) if (r.ch[i] != 0 || r.bg[i] != 0 || r.at[i] != 0) return false;
        return true;
    }

    private Row rowForReflow(int idx) { return idx < sbCount ? sb[(sbStart + idx) % maxSb] : scr[idx - sbCount]; }

    /** Re-wraps scrollback and screen to a new width (main screen only). */
    private void reflow(int newCols, int newRows) {
        int lastScr = curY;
        for (int y = rows - 1; y > curY; y--) if (!isBlankRow(scr[y])) { lastScr = y; break; }
        int count = sbCount + lastScr + 1;
        int cursorAbs = sbCount + curY;

        java.util.ArrayList<Row> out = new java.util.ArrayList<>();
        int cRow = 0, cCol = 0;
        int i = 0;
        while (i < count) {
            int j = i;
            while (j < count - 1 && rowForReflow(j).wrapped) j++;

            // gather the logical line i..j as flat cell arrays
            int total = 0;
            for (int k = i; k <= j; k++) total += rowForReflow(k).ch.length;
            int[] ch = new int[total], fg = new int[total], bg = new int[total], at = new int[total];
            int pos = 0, cursorIdx = -1;
            for (int k = i; k <= j; k++) {
                Row r = rowForReflow(k);
                if (k == cursorAbs) cursorIdx = pos + Math.min(curX, r.ch.length - 1);
                System.arraycopy(r.ch, 0, ch, pos, r.ch.length);
                System.arraycopy(r.fg, 0, fg, pos, r.ch.length);
                System.arraycopy(r.bg, 0, bg, pos, r.ch.length);
                System.arraycopy(r.at, 0, at, pos, r.ch.length);
                pos += r.ch.length;
            }
            int len = total;
            while (len > 0 && ch[len - 1] == 0 && bg[len - 1] == 0 && at[len - 1] == 0) len--;

            Row cur = new Row(newCols, 0);
            int col = 0;
            for (int c = 0; c < len; c++) {
                if ((at[c] & AT_WIDE_CONT) != 0) continue;
                boolean wide = c + 1 < total && (at[c + 1] & AT_WIDE_CONT) != 0;
                int w = wide ? 2 : 1;
                if (col + w > newCols) { cur.wrapped = true; out.add(cur); cur = new Row(newCols, 0); col = 0; }
                if (c == cursorIdx) { cRow = out.size(); cCol = col; }
                cur.ch[col] = ch[c]; cur.fg[col] = fg[c]; cur.bg[col] = bg[c]; cur.at[col] = at[c];
                if (wide) { cur.ch[col + 1] = 0; cur.fg[col + 1] = fg[c + 1]; cur.bg[col + 1] = bg[c + 1]; cur.at[col + 1] = at[c + 1]; }
                col += w;
            }
            if (cursorIdx >= len) {              // cursor sits after the text (or in blank space)
                int extra = cursorIdx - len;
                col += extra;
                if (col >= newCols) { cur.wrapped = true; out.add(cur); cur = new Row(newCols, 0); col = 0; }
                cRow = out.size(); cCol = col;
            }
            out.add(cur);
            i = j + 1;
        }
        if (out.isEmpty()) out.add(new Row(newCols, 0));

        int end = Math.max(out.size(), cRow + 1);
        int start = Math.max(0, end - newRows);
        dropped += sbCount;                    // every old line id is void after a re-wrap
        sbStart = 0; sbCount = 0;
        for (int r = 0; r < start; r++) pushScrollback(out.get(r));
        Row[] ns = new Row[newRows];
        for (int y = 0; y < newRows; y++) ns[y] = (start + y < out.size()) ? out.get(start + y) : new Row(newCols, 0);
        scr = ns;
        curY = Math.max(0, Math.min(newRows - 1, cRow - start));
        curX = Math.min(cCol, newCols - 1);

        boolean[] nt = new boolean[newCols];
        for (int k = 0; k < newCols; k++) nt[k] = k < tabs.length ? tabs[k] : (k % 8 == 0);
        tabs = nt;
        cols = newCols; rows = newRows;
        top = 0; bottom = rows - 1;
        savedX = Math.min(savedX, cols - 1); savedY = Math.min(savedY, rows - 1);
        wrapPending = false;
    }

    private void plainResize(int newCols, int newRows) {

        Row[] ns = new Row[newRows];
        if (!usingAlt) {
            // keep the cursor line on screen: push top lines to scrollback when shrinking,
            // pull them back when growing
            int shift = 0;
            if (newRows < rows) {
                shift = -Math.max(0, (curY + 1) - newRows);
                for (int i = 0; i < -shift; i++) pushScrollback(scr[i]);
            } else if (newRows > rows) {
                shift = Math.min(newRows - rows, sbCount);
            }
            // shift > 0: take lines from scrollback and put them on top
            int srcFirst = shift < 0 ? -shift : 0;
            int dst = shift > 0 ? shift : 0;
            for (int i = 0; i < shift; i++) ns[i] = popScrollback();
            // popScrollback returned newest first; fix order
            for (int i = 0; i < shift / 2; i++) { Row t = ns[i]; ns[i] = ns[shift - 1 - i]; ns[shift - 1 - i] = t; }
            for (int i = srcFirst; i < rows && dst < newRows; i++, dst++) ns[dst] = scr[i];
            curY = Math.max(0, Math.min(newRows - 1, curY + shift));
        } else {
            for (int i = 0; i < newRows; i++) ns[i] = i < rows ? scr[i] : null;
            curY = Math.min(curY, newRows - 1);
        }
        for (int i = 0; i < newRows; i++) ns[i] = ns[i] == null ? new Row(newCols, 0) : ns[i].resized(newCols, 0);
        scr = ns;

        if (saved != null) {
            Row[] nv = new Row[newRows];
            for (int i = 0; i < newRows; i++)
                nv[i] = i < saved.length ? saved[i].resized(newCols, 0) : new Row(newCols, 0);
            saved = nv;
        }
        boolean[] nt = new boolean[newCols];
        for (int i = 0; i < newCols; i++) nt[i] = i < tabs.length ? tabs[i] : (i % 8 == 0);
        tabs = nt;
        cols = newCols; rows = newRows;
        top = 0; bottom = rows - 1;
        curX = Math.min(curX, cols - 1);
        wrapPending = false;
    }

    // ------------------------------------------------------------------ internals

    private void reset() {
        scr = new Row[rows];
        for (int i = 0; i < rows; i++) scr[i] = new Row(cols, 0);
        saved = null; usingAlt = false;
        dropped += sbCount;
        sbStart = sbCount = 0;
        curX = curY = 0; savedX = savedY = 0;
        top = 0; bottom = rows - 1;
        fg = bg = at = 0;
        wrapPending = false; autowrap = true; insertMode = false; cursorVisible = true;
        appCursorKeys = false; bracketedPaste = false; mouseMode = 0; mouseSgr = false;
        g[0] = g[1] = 0; gsel = 0;
        tabs = new boolean[cols];
        for (int i = 0; i < cols; i += 8) tabs[i] = true;
        state = S_GROUND; utfNeed = 0;
    }

    private void respond(String s) {
        if (responder != null) responder.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** Drops the oldest history lines so that at most {@code keep} remain (used when memory is short). */
    public synchronized void trimScrollback(int keep) {
        if (sbCount <= keep) return;
        int drop = sbCount - keep;
        for (int i = 0; i < drop; i++) sb[(sbStart + i) % maxSb] = null;
        sbStart = (sbStart + drop) % maxSb;
        sbCount = keep;
        dropped += drop;
    }

    /** Returns the row that fell out of the history (to be reused), or null while it is still filling up. */
    private Row pushScrollback(Row r) {
        if (sbCount < maxSb) { sb[(sbStart + sbCount++) % maxSb] = r; return null; }
        Row old = sb[sbStart];
        sb[sbStart] = r;
        sbStart = (sbStart + 1) % maxSb;
        dropped++;
        return old;
    }

    /** A blank row, reusing an old one's arrays when it has the right width. */
    private Row blankRow(Row reuse) {
        if (reuse == null || reuse.ch.length != cols) return new Row(cols, bg);
        java.util.Arrays.fill(reuse.ch, 0);
        java.util.Arrays.fill(reuse.fg, 0);
        java.util.Arrays.fill(reuse.bg, bg);
        java.util.Arrays.fill(reuse.at, 0);
        reuse.wrapped = false;
        return reuse;
    }

    private Row popScrollback() {
        if (sbCount == 0) return new Row(cols, 0);
        Row r = sb[(sbStart + sbCount - 1) % maxSb];
        sbCount--;
        return r;
    }

    private Row blankRow() { return new Row(cols, bg); }

    private void handle(int c) {
        // controls that act in every state
        if (c == 0x18 || c == 0x1A) { state = S_GROUND; return; }
        switch (state) {
            case S_GROUND: ground(c); break;
            case S_ESC: esc(c); break;
            case S_INTER: inter(c); break;
            case S_CSI: csi(c); break;
            case S_OSC:
                if (c == 0x07) { endOsc(); }
                else if (c == 0x1B) state = S_OSC_ESC;
                else if (osc.length() < 2048) osc.appendCodePoint(c);
                break;
            case S_OSC_ESC:
                if (c == '\\') endOsc();
                else { state = S_ESC; esc(c); }
                break;
            case S_STR:
                if (c == 0x07) state = S_GROUND; else if (c == 0x1B) state = S_STR_ESC;
                break;
            case S_STR_ESC:
                state = (c == '\\') ? S_GROUND : S_STR;
                break;
        }
    }

    private void endOsc() {
        state = S_GROUND;
        String s = osc.toString();
        int semi = s.indexOf(';');
        if (semi > 0) {
            String cmd = s.substring(0, semi);
            if (cmd.equals("0") || cmd.equals("2")) title = s.substring(semi + 1);
        }
    }

    private void ground(int c) {
        if (c < 0x20) { control(c); return; }
        if (c == 0x7F) return;
        if (c >= 0x80 && c < 0xA0) return;
        put(c);
    }

    private void control(int c) {
        switch (c) {
            case 0x07: break;                              // BEL
            case 0x08: if (curX > 0) curX--; wrapPending = false; break;
            case 0x09: tab(1); break;
            case 0x0A: case 0x0B: case 0x0C: lineFeed(); wrapPending = false; break;
            case 0x0D: curX = 0; wrapPending = false; break;
            case 0x0E: gsel = 1; break;
            case 0x0F: gsel = 0; break;
            case 0x1B: state = S_ESC; break;
            default: break;
        }
    }

    private void tab(int n) {
        wrapPending = false;
        for (; n > 0; n--) { do curX++; while (curX < cols - 1 && !tabs[curX]); if (curX >= cols - 1) { curX = cols - 1; break; } }
    }

    private void backTab(int n) {
        wrapPending = false;
        for (; n > 0; n--) { do curX--; while (curX > 0 && !tabs[curX]); if (curX <= 0) { curX = 0; break; } }
    }

    private void esc(int c) {
        state = S_GROUND;
        switch (c) {
            case '[': state = S_CSI; pc = 0; pHas = false; pCur = 0; csiPriv = 0; csiInter = 0; break;
            case ']': state = S_OSC; osc.setLength(0); break;
            case 'P': case '_': case '^': case 'X': state = S_STR; break;
            case '(': case ')': case '*': case '+': case '#': case '%': case ' ':
                state = S_INTER; interChar = (char) c; break;
            case '7': savedX = curX; savedY = curY; savedFg = fg; savedBg = bg; savedAt = at; break;
            case '8': curX = Math.min(savedX, cols - 1); curY = Math.min(savedY, rows - 1); fg = savedFg; bg = savedBg; at = savedAt; wrapPending = false; break;
            case 'D': lineFeed(); break;
            case 'E': curX = 0; lineFeed(); break;
            case 'H': tabs[Math.min(curX, cols - 1)] = true; break;
            case 'M': reverseIndex(); break;
            case 'c': reset(); break;
            case '=': case '>': break;
            case 0x1B: state = S_ESC; break;
            default:
                if (c < 0x20) control(c);
                break;
        }
    }

    private void inter(int c) {
        state = S_GROUND;
        if (interChar == '(') g[0] = (c == '0') ? 1 : 0;
        else if (interChar == ')') g[1] = (c == '0') ? 1 : 0;
        else if (interChar == '#' && c == '8') { // DECALN
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) { scr[y].ch[x] = 'E'; scr[y].at[x] = 0; }
        }
    }

    // -------------------------------------------------------------- CSI

    private void csi(int c) {
        if (c < 0x20) { if (c == 0x1B) state = S_ESC; else control(c); return; }
        if (c >= '0' && c <= '9') { pHas = true; pCur = Math.min(pCur * 10 + (c - '0'), 65535); return; }
        if (c == ';' || c == ':') { pushParam(); return; }
        if (c >= '<' && c <= '?') { csiPriv = c; return; }
        if (c >= 0x20 && c <= 0x2F) { csiInter = c; return; }
        if (c >= 0x40 && c <= 0x7E) {
            if (pHas || pc > 0) pushParam();
            state = S_GROUND;
            dispatch((char) c);
            return;
        }
        state = S_GROUND; // garbage
    }

    private void pushParam() {
        if (pc < params.length) params[pc++] = pCur;
        pCur = 0; pHas = false;
    }

    private int p(int i, int def) { return (i < pc && params[i] > 0) ? params[i] : def; }
    private int raw(int i) { return i < pc ? params[i] : 0; }

    private void dispatch(char f) {
        if (csiInter == ' ' ) return; // cursor style etc.
        switch (f) {
            case '@': insertChars(p(0, 1)); break;
            case 'A': curY = Math.max(curY >= top ? top : 0, curY - p(0, 1)); wrapPending = false; break;
            case 'B': case 'e': curY = Math.min(curY <= bottom ? bottom : rows - 1, curY + p(0, 1)); wrapPending = false; break;
            case 'C': case 'a': curX = Math.min(cols - 1, curX + p(0, 1)); wrapPending = false; break;
            case 'D': curX = Math.max(0, curX - p(0, 1)); wrapPending = false; break;
            case 'E': curX = 0; curY = Math.min(rows - 1, curY + p(0, 1)); wrapPending = false; break;
            case 'F': curX = 0; curY = Math.max(0, curY - p(0, 1)); wrapPending = false; break;
            case 'G': case '`': curX = Math.min(cols, p(0, 1)) - 1; wrapPending = false; break;
            case 'H': case 'f': curY = Math.min(rows, p(0, 1)) - 1; curX = Math.min(cols, p(1, 1)) - 1; wrapPending = false; break;
            case 'I': tab(p(0, 1)); break;
            case 'Z': backTab(p(0, 1)); break;
            case 'J': eraseDisplay(raw(0)); break;
            case 'K': eraseLine(raw(0)); break;
            case 'L': if (curY >= top && curY <= bottom) scrollDownRegion(curY, bottom, p(0, 1)); break;
            case 'M': if (curY >= top && curY <= bottom) scrollUpRegion(curY, bottom, p(0, 1), false); break;
            case 'P': deleteChars(p(0, 1)); break;
            case 'S': scrollUpRegion(top, bottom, p(0, 1), true); break;
            case 'T': if (pc <= 1) scrollDownRegion(top, bottom, p(0, 1)); break;
            case 'X': { Row r = scr[curY]; int n = Math.min(p(0, 1), cols - curX); for (int i = 0; i < n; i++) clearCell(r, curX + i); break; }
            case 'b': for (int i = 0, n = p(0, 1); i < n && lastChar != 0; i++) put(lastChar); break;
            case 'c':
                if (csiPriv == '>') respond("\033[>0;115;0c");
                else if (csiPriv == 0) respond("\033[?62;c");
                break;
            case 'd': curY = Math.min(rows, p(0, 1)) - 1; wrapPending = false; break;
            case 'g': if (raw(0) == 0) tabs[Math.min(curX, cols - 1)] = false; else if (raw(0) == 3) java.util.Arrays.fill(tabs, false); break;
            case 'h': mode(true); break;
            case 'l': mode(false); break;
            case 'm': sgr(); break;
            case 'n':
                if (csiPriv == 0) {
                    if (raw(0) == 5) respond("\033[0n");
                    else if (raw(0) == 6) respond("\033[" + (curY + 1) + ";" + (curX + 1) + "R");
                }
                break;
            case 'r': {
                int t = p(0, 1) - 1, b = p(1, rows) - 1;
                if (t < b && b < rows) { top = t; bottom = b; curX = 0; curY = 0; wrapPending = false; }
                break;
            }
            case 's': if (csiPriv == 0) { savedX = curX; savedY = curY; savedFg = fg; savedBg = bg; savedAt = at; } break;
            case 'u': if (csiPriv == 0) { curX = Math.min(savedX, cols - 1); curY = Math.min(savedY, rows - 1); wrapPending = false; } break;
            default: break; // t (window ops), q, etc.
        }
    }

    private void mode(boolean set) {
        for (int i = 0; i < pc; i++) {
            int m = params[i];
            if (csiPriv == '?') {
                switch (m) {
                    case 1: appCursorKeys = set; break;
                    case 7: autowrap = set; break;
                    case 25: cursorVisible = set; break;
                    case 47: case 1047: switchScreen(set, false); break;
                    case 1048: if (set) { savedX = curX; savedY = curY; savedFg = fg; savedBg = bg; savedAt = at; } else { curX = Math.min(savedX, cols - 1); curY = Math.min(savedY, rows - 1); } break;
                    case 1049: switchScreen(set, true); break;
                    case 2004: bracketedPaste = set; break;
                    case 1000: case 1002: case 1003: mouseMode = set ? m : 0; break;
                    case 1006: mouseSgr = set; break;
                    default: break;
                }
            } else if (csiPriv == 0) {
                if (m == 4) insertMode = set;
            }
        }
    }

    private void switchScreen(boolean toAlt, boolean saveCursor) {
        if (toAlt == usingAlt) return;
        if (toAlt) {
            if (saveCursor) { savedX = curX; savedY = curY; savedFg = fg; savedBg = bg; savedAt = at; }
            saved = scr;
            scr = new Row[rows];
            for (int i = 0; i < rows; i++) scr[i] = new Row(cols, 0);
            usingAlt = true;
            top = 0; bottom = rows - 1;
        } else {
            if (saved != null) scr = saved;
            saved = null;
            usingAlt = false;
            top = 0; bottom = rows - 1;
            if (saveCursor) { curX = Math.min(savedX, cols - 1); curY = Math.min(savedY, rows - 1); fg = savedFg; bg = savedBg; at = savedAt; }
        }
        wrapPending = false;
    }

    private void sgr() {
        if (pc == 0) { fg = bg = at = 0; return; }
        for (int i = 0; i < pc; i++) {
            int v = params[i];
            if (v == 0) { fg = bg = at = 0; }
            else if (v == 1) at |= AT_BOLD;
            else if (v == 2) at |= AT_DIM;
            else if (v == 3) at |= AT_ITALIC;
            else if (v == 4) at |= AT_UNDER;
            else if (v == 7) at |= AT_INVERSE;
            else if (v == 8) at |= AT_INVIS;
            else if (v == 9) at |= AT_STRIKE;
            else if (v == 22) at &= ~(AT_BOLD | AT_DIM);
            else if (v == 23) at &= ~AT_ITALIC;
            else if (v == 24) at &= ~AT_UNDER;
            else if (v == 27) at &= ~AT_INVERSE;
            else if (v == 28) at &= ~AT_INVIS;
            else if (v == 29) at &= ~AT_STRIKE;
            else if (v >= 30 && v <= 37) fg = PAL[v - 30];
            else if (v == 39) fg = 0;
            else if (v >= 40 && v <= 47) bg = PAL[v - 40];
            else if (v == 49) bg = 0;
            else if (v >= 90 && v <= 97) fg = PAL[v - 90 + 8];
            else if (v >= 100 && v <= 107) bg = PAL[v - 100 + 8];
            else if (v == 38 || v == 48) {
                int color = 0;
                if (i + 1 < pc && params[i + 1] == 5 && i + 2 < pc) { color = PAL[params[i + 2] & 0xFF]; i += 2; }
                else if (i + 1 < pc && params[i + 1] == 2 && i + 4 < pc) {
                    color = 0xFF000000 | ((params[i + 2] & 0xFF) << 16) | ((params[i + 3] & 0xFF) << 8) | (params[i + 4] & 0xFF);
                    i += 4;
                } else break;
                if (v == 38) fg = color; else bg = color;
            }
        }
    }

    // -------------------------------------------------------------- screen operations

    private static int width(int cp) {
        if (cp < 0x300) return 1;
        if ((cp >= 0x300 && cp <= 0x36F) || (cp >= 0x200B && cp <= 0x200F) || (cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0xFEFF) return 0;
        if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1F64F) || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x3FFFD)) return 2;
        return 1;
    }

    private void put(int cp) {
        if (g[gsel] == 1 && cp >= 0x5F && cp <= 0x7E) cp = GFX[cp - 0x5F];
        int w = width(cp);
        if (w == 0) return;
        if (wrapPending) {
            if (autowrap) { scr[curY].wrapped = true; curX = 0; lineFeed(); }
            wrapPending = false;
        }
        if (w == 2 && curX >= cols - 1) {
            if (!autowrap) return;
            scr[curY].wrapped = true;
            curX = 0; lineFeed();
        }
        if (insertMode) insertChars(w);
        Row r = scr[curY];
        r.ch[curX] = cp; r.fg[curX] = fg; r.bg[curX] = bg; r.at[curX] = at;
        if (w == 2) { r.ch[curX + 1] = 0; r.fg[curX + 1] = fg; r.bg[curX + 1] = bg; r.at[curX + 1] = at | AT_WIDE_CONT; }
        curX += w;
        if (curX >= cols) { curX = cols - 1; wrapPending = true; }
        lastChar = cp;
    }

    private void clearCell(Row r, int x) { r.ch[x] = 0; r.fg[x] = 0; r.bg[x] = bg; r.at[x] = 0; }

    private void lineFeed() {
        if (curY == bottom) scrollUpRegion(top, bottom, 1, true);
        else if (curY < rows - 1) curY++;
    }

    private void reverseIndex() {
        if (curY == top) scrollDownRegion(top, bottom, 1);
        else if (curY > 0) curY--;
    }

    /** Remove n lines at 'from', pull up lines below within region, blank at the bottom. */
    private void scrollUpRegion(int from, int to, int n, boolean allowScrollback) {
        n = Math.min(n, to - from + 1);
        for (int k = 0; k < n; k++) {
            Row removed = scr[from];
            Row spare = removed;                                   // not kept anywhere: reuse it
            if (allowScrollback && from == 0 && !usingAlt && to == rows - 1) spare = pushScrollback(removed);
            System.arraycopy(scr, from + 1, scr, from, to - from);
            scr[to] = blankRow(spare);
        }
    }

    private void scrollDownRegion(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        for (int k = 0; k < n; k++) {
            System.arraycopy(scr, from, scr, from + 1, to - from);
            scr[from] = blankRow();
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            Row r = scr[curY]; for (int x = curX; x < cols; x++) clearCell(r, x);
            for (int y = curY + 1; y < rows; y++) scr[y] = blankRow();
        } else if (mode == 1) {
            for (int y = 0; y < curY; y++) scr[y] = blankRow();
            Row r = scr[curY]; for (int x = 0; x <= curX && x < cols; x++) clearCell(r, x);
        } else if (mode == 2) {
            for (int y = 0; y < rows; y++) scr[y] = blankRow();
        } else if (mode == 3) {
            dropped += sbCount; sbCount = 0; sbStart = 0;
        }
    }

    private void eraseLine(int mode) {
        Row r = scr[curY];
        int from = mode == 0 ? curX : 0;
        int to = mode == 1 ? curX : cols - 1;
        for (int x = from; x <= to && x < cols; x++) clearCell(r, x);
        if (to >= cols - 1) r.wrapped = false;
    }

    private void insertChars(int n) {
        Row r = scr[curY];
        n = Math.min(n, cols - curX);
        for (int x = cols - 1; x >= curX + n; x--) copyCell(r, x - n, x);
        for (int x = curX; x < curX + n; x++) clearCell(r, x);
    }

    private void deleteChars(int n) {
        Row r = scr[curY];
        n = Math.min(n, cols - curX);
        for (int x = curX; x < cols - n; x++) copyCell(r, x + n, x);
        for (int x = cols - n; x < cols; x++) clearCell(r, x);
    }

    private static void copyCell(Row r, int from, int to) {
        r.ch[to] = r.ch[from]; r.fg[to] = r.fg[from]; r.bg[to] = r.bg[from]; r.at[to] = r.at[from];
    }
}
