package com.zed.nullshell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/** Renders a {@link TermEmulator} and turns keyboard / touch input into bytes for the PTY. */
public class TerminalView extends View {
    public interface ModifierListener { void onModifiersChanged(boolean ctrl, boolean alt); }

    private int defFg = 0xFFE0E0E0, defBg = 0xFF101010;

    private KaliSession session;
    private TermEmulator emu;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private float charW, lineH, ascent, textPx;
    private int cols = 80, rows = 24;

    private float posPx;             // pixels scrolled back from the live bottom; 0 = following new output
    private long lastTotal = -1;     // scrollback + dropped lines at the previous draw, used to keep the view anchored
    private long curDropped;         // dropped-line count of the frame being drawn
    private boolean frozen;          // hold the view still while output keeps arriving (during selection)
    private boolean freezeFromLive;  // the freeze started at the bottom, so go back there afterwards
    private final OverScroller scroller;
    private float scrollAccum;

    private boolean ctrlSticky, altSticky;
    private ModifierListener modListener;

    private boolean hasSelection;
    private long selR1, selR2;                // stable line ids (absolute row + dropped lines)
    private int selC1, selC2;                 // (R1,C1) is always <= (R2,C2)
    private int dragHandle;                   // 0 none, 1 = start handle, 2 = end handle
    private boolean dragFromLongPress;
    private ActionMode actionMode;
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float handleR;

    private int gestureAxis;                  // 0 unknown, 1 vertical, 2 horizontal
    private float hAccum;

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private float pendingScale = 1f;

    public TerminalView(Context c, AttributeSet a) {
        super(c, a);
        setFocusable(true);
        setFocusableInTouchMode(true);
        scroller = new OverScroller(c);
        paint.setTypeface(Typeface.MONOSPACE);
        handleR = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9, getResources().getDisplayMetrics());
        handlePaint.setColor(0xFF00BFA5);
        setTextSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));

        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                gestureAxis = 0; scrollAccum = 0; hAccum = 0;
                scroller.forceFinished(true);
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (hasSelection) { clearSelection(); return true; }
                requestFocus();
                showKeyboard();
                if (emu != null && emu.getMouseMode() != 0) {      // apps with mouse support (nano, vim, htop, mc)
                    int[] rc = screenCell(e.getX(), e.getY());
                    sendMouse(0, rc[1], rc[0], true);
                    sendMouse(0, rc[1], rc[0], false);
                }
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (emu == null) return true;
                boolean mouse = emu.getMouseMode() != 0, alt = emu.isAltScreen();
                if (!mouse && !alt) {
                    // pixel-accurate drag; finger moving down (dy < 0) reveals older lines
                    posPx = Math.max(0, Math.min(maxPos(), posPx - dy));
                    invalidate();
                    return true;
                }
                if (gestureAxis == 0) gestureAxis = Math.abs(dx) > Math.abs(dy) ? 2 : 1;
                if (gestureAxis == 1) {
                    // Full-screen apps: swipe up = scroll down. Mouse apps get wheel events, others arrow keys.
                    scrollAccum += dy;
                    int lines = (int) (scrollAccum / lineH);
                    if (lines != 0) {
                        scrollAccum -= lines * lineH;
                        int n = Math.min(Math.abs(lines), 20);
                        if (mouse) {
                            int[] rc = screenCell(e2.getX(), e2.getY());
                            for (int i = 0; i < n; i++) sendMouse(lines > 0 ? 65 : 64, rc[1], rc[0], true);
                        } else {
                            for (int i = 0; i < n; i++) sendArrow(lines > 0 ? 'B' : 'A');
                        }
                    }
                } else if (!mouse) {
                    // Horizontal swipe moves the cursor with the finger.
                    hAccum -= dx;
                    int cells = (int) (hAccum / charW);
                    if (cells != 0) {
                        hAccum -= cells * charW;
                        int n = Math.min(Math.abs(cells), 20);
                        for (int i = 0; i < n; i++) sendArrow(cells > 0 ? 'C' : 'D');
                    }
                }
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (emu == null || emu.getMouseMode() != 0 || emu.isAltScreen() || dragHandle != 0) return false;
                scroller.fling(0, (int) posPx, 0, (int) vy, 0, 0, 0, (int) maxPos());
                postInvalidateOnAnimation();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) { startWordSelection(e.getX(), e.getY()); }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                pendingScale *= d.getScaleFactor();
                if (pendingScale > 1.08f || pendingScale < 0.92f) {
                    float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 7, getResources().getDisplayMetrics());
                    float max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32, getResources().getDisplayMetrics());
                    setTextSizePx(Math.max(min, Math.min(max, textPx * pendingScale)));
                    pendingScale = 1f;
                    recomputeGrid(getWidth(), getHeight());
                    invalidate();
                }
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                float oneSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());
                Prefs.sp(getContext()).edit().putInt(Prefs.FONT_SIZE, Math.round(textPx / oneSp)).apply();
            }
        });
    }

    // ------------------------------------------------------------------ setup

    public void attach(KaliSession s) {
        session = s;
        emu = s.emulator();
        posPx = 0;
        scrollAccum = 0;
        lastTotal = -1;
        frozen = false;
        freezeFromLive = false;
        scroller.forceFinished(true);
        clearSelection();
        recomputeGrid(getWidth(), getHeight());
        invalidate();
    }

    public KaliSession getSession() { return session; }

    private float maxPos() { return emu == null ? 0 : emu.getScrollbackSize() * lineH; }
    private int back() { return lineH <= 0 ? 0 : (int) (posPx / lineH); }
    private float frac() { return posPx - back() * lineH; }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            posPx = Math.max(0, Math.min(scroller.getCurrY(), maxPos()));
            postInvalidateOnAnimation();
        }
    }

    /** Applies font size (sp) and default colours from the settings screen. */
    public void applySettings(int fontSp, int fg, int bg) {
        defFg = fg;
        defBg = bg;
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, getResources().getDisplayMetrics());
        if (Math.abs(px - textPx) > 0.5f) {
            setTextSizePx(px);
            recomputeGrid(getWidth(), getHeight());
        }
        invalidate();
    }

    public void setModifierListener(ModifierListener l) { modListener = l; }
    public int getCols() { return cols; }
    public int getRows() { return rows; }

    private void setTextSizePx(float px) {
        textPx = px;
        paint.setTextSize(px);
        Paint.FontMetrics fm = paint.getFontMetrics();
        charW = paint.measureText("M");
        lineH = (float) Math.ceil(fm.descent - fm.ascent);
        ascent = fm.ascent;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        recomputeGrid(w, h);
    }

    private void recomputeGrid(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int nc = Math.max(10, (int) (w / charW));
        int nr = Math.max(3, (int) (h / lineH));
        cols = nc; rows = nr;
        if (session != null) session.resize(nc, nr);
        posPx = Math.min(posPx, maxPos());
    }

    public void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    public void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        requestFocus();
        imm.toggleSoftInput(0, 0);
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(defBg);
        if (emu == null) return;
        synchronized (emu) {
            int sb = emu.getScrollbackSize();
            long dropped = emu.getDroppedLines();
            curDropped = dropped;
            long total = sb + dropped;
            // While scrolled back (or frozen for a selection) new lines must not drag the view along.
            if (lastTotal >= 0 && total != lastTotal && (frozen || posPx > 0)) {
                posPx += (total - lastTotal) * lineH;
                if (!scroller.isFinished()) scroller.forceFinished(true);
            }
            lastTotal = total;
            if (emu.isAltScreen()) posPx = 0;
            posPx = Math.max(0, Math.min(posPx, sb * lineH));
            int back = (int) (posPx / lineH);
            float frac = posPx - back * lineH;

            int nrows = Math.min(rows, emu.getRows());
            int ncols = emu.getCols();
            boolean showCursor = emu.isCursorVisible() && posPx == 0;
            int cx = emu.getCursorX(), cy = emu.getCursorY();

            for (int y = frac > 0 ? -1 : 0; y < nrows; y++) {
                int abs = sb + y - back;
                if (abs < 0) continue;
                TermEmulator.Row r = emu.absRow(abs);
                int n = Math.min(r.ch.length, ncols);
                float top = y * lineH + frac;
                float base = top - ascent;
                int x = 0;
                while (x < n) {
                    boolean cur = showCursor && y == cy && x == cx;
                    long st = style(r, x, abs, cur);
                    int x2 = x + 1;
                    while (x2 < n && !(showCursor && y == cy && x2 == cx) && style(r, x2, abs, false) == st
                            && flags(r, x2) == flags(r, x)) x2++;

                    int rf = (int) (st >> 32), rb = (int) st;
                    int at = r.at[x];
                    if (rb != defBg) {
                        bgPaint.setColor(rb);
                        canvas.drawRect(x * charW, top, x2 * charW, top + lineH, bgPaint);
                    }
                    if ((at & TermEmulator.AT_INVIS) == 0) {
                        StringBuilder sbd = null;
                        boolean any = false;
                        for (int k = x; k < x2; k++) {
                            if ((r.at[k] & TermEmulator.AT_WIDE_CONT) != 0) continue;
                            int cp = r.ch[k];
                            if (cp != 0 && cp != ' ') any = true;
                            if (sbd == null) sbd = new StringBuilder();
                            sbd.appendCodePoint(cp == 0 ? ' ' : cp);
                        }
                        if (any || (at & (TermEmulator.AT_UNDER | TermEmulator.AT_STRIKE)) != 0) {
                            paint.setColor(rf);
                            paint.setFakeBoldText((at & TermEmulator.AT_BOLD) != 0);
                            paint.setTextSkewX((at & TermEmulator.AT_ITALIC) != 0 ? -0.2f : 0f);
                            paint.setUnderlineText((at & TermEmulator.AT_UNDER) != 0);
                            paint.setStrikeThruText((at & TermEmulator.AT_STRIKE) != 0);
                            canvas.drawText(sbd.toString(), x * charW, base, paint);
                        }
                    }
                    x = x2;
                }
            }
            if (hasSelection) {
                for (int h = 1; h <= 2; h++) {
                    float[] hp = handlePos(h);
                    if (hp != null) canvas.drawCircle(hp[0], hp[1], handleR, handlePaint);
                }
            }
        }
    }

    /** Resolved (fg, bg) of a cell packed as (fg << 32) | bg. Attribute flags are compared separately via flags(). */
    private long style(TermEmulator.Row r, int x, int abs, boolean cursor) {
        int f = r.fg[x] == 0 ? defFg : r.fg[x];
        int b = r.bg[x] == 0 ? defBg : r.bg[x];
        boolean inv = (r.at[x] & TermEmulator.AT_INVERSE) != 0;
        if (hasSelection && isSelected(abs, x)) inv = !inv;
        if (cursor) inv = !inv;
        if ((r.at[x] & TermEmulator.AT_DIM) != 0) f = (f & 0xFF000000) | ((f & 0xFEFEFE) >> 1);
        if (inv) { int t = f; f = b; b = t; }
        return ((long) f << 32) | (b & 0xFFFFFFFFL);
    }

    private static final int FLAG_MASK = TermEmulator.AT_BOLD | TermEmulator.AT_ITALIC | TermEmulator.AT_UNDER
            | TermEmulator.AT_STRIKE | TermEmulator.AT_INVIS;

    private static int flags(TermEmulator.Row r, int x) { return r.at[x] & FLAG_MASK; }

    private boolean isSelected(int abs, int x) {
        long id = abs + curDropped;
        long r1 = selR1, r2 = selR2;
        int c1 = selC1, c2 = selC2;
        if (r1 > r2 || (r1 == r2 && c1 > c2)) { long t = r1; r1 = r2; r2 = t; int u = c1; c1 = c2; c2 = u; }
        if (id < r1 || id > r2) return false;
        if (id == r1 && x < c1) return false;
        if (id == r2 && x > c2) return false;
        return true;
    }

    /** Cell under a touch point as {stable line id, column}. */
    private long[] cellAt(float px, float py) {
        int col = Math.max(0, Math.min(cols - 1, (int) (px / charW)));
        int row = (int) Math.floor((py - frac()) / lineH);
        row = Math.max(-1, Math.min(rows - 1, row));
        int abs = Math.max(0, emu.getScrollbackSize() + row - back());
        return new long[]{abs + emu.getDroppedLines(), col};
    }

    private int toAbs(long id) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE, id - emu.getDroppedLines())); }

    // ------------------------------------------------------------------ touch

    private int[] screenCell(float px, float py) {
        int col = Math.max(0, Math.min(cols - 1, (int) (px / charW)));
        int row = Math.max(0, Math.min(rows - 1, (int) (py / lineH)));
        return new int[]{row, col};
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (emu == null) return super.onTouchEvent(ev);
        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN && hasSelection) {
            int h = hitHandle(ev.getX(), ev.getY());
            if (h != 0) { dragHandle = h; dragFromLongPress = false; return true; }
        }
        if (dragHandle != 0) {
            if (action == MotionEvent.ACTION_MOVE) {
                dragTo(ev.getX(), ev.getY());
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragHandle = 0;
                if (dragFromLongPress) {          // let the GestureDetector forget the long press
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    gestures.onTouchEvent(cancel);
                    cancel.recycle();
                }
                dragFromLongPress = false;
                showSelectionToolbar();
            }
            return true;
        }

        scaler.onTouchEvent(ev);
        if (!scaler.isInProgress()) gestures.onTouchEvent(ev);
        return true;
    }

    // ------------------------------------------------------------------ selection

    private static boolean isWordCell(TermEmulator.Row r, int x) {
        int cp = r.ch[x];
        return (r.at[x] & TermEmulator.AT_WIDE_CONT) != 0 || (cp != 0 && cp != ' ');
    }

    /** Long press selects the word under the finger; continuing to drag extends the end. */
    private void startWordSelection(float x, float y) {
        if (emu == null) return;
        long[] rc = cellAt(x, y);
        long id = rc[0];
        int col = (int) rc[1], c1 = col, c2 = col;
        synchronized (emu) {
            TermEmulator.Row r = emu.absRow(toAbs(id));
            int n = r.ch.length;
            if (col < n && isWordCell(r, col)) {
                while (c1 > 0 && isWordCell(r, c1 - 1)) c1--;
                while (c2 < n - 1 && isWordCell(r, c2 + 1)) c2++;
            }
        }
        selR1 = selR2 = id; selC1 = c1; selC2 = c2;
        hasSelection = true;
        scroller.forceFinished(true);
        if (posPx == 0 && !emu.isAltScreen()) {      // output keeps scrolling: hold the picture still while selecting
            frozen = true;
            freezeFromLive = true;
        }
        dragHandle = 2;
        dragFromLongPress = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private float[] handlePos(int which) {
        long id = which == 1 ? selR1 : selR2;
        int c = which == 1 ? selC1 : selC2 + 1;
        long sy = (id - emu.getDroppedLines()) - (emu.getScrollbackSize() - back());
        if (sy < 0 || sy >= rows) return null;
        return new float[]{c * charW, (sy + 1) * lineH + frac() + handleR * 0.9f};
    }

    private int hitHandle(float x, float y) {
        float reach = handleR * 3.2f, best = reach * reach;
        int found = 0;
        for (int h = 1; h <= 2; h++) {
            float[] p = handlePos(h);
            if (p == null) continue;
            float dx = p[0] - x, dy = p[1] - y, d = dx * dx + dy * dy;
            if (d <= best) { best = d; found = h; }
        }
        return found;
    }

    private void dragTo(float x, float y) {
        // edge auto-scroll, one line per move event, so a selection can reach into the scrollback
        if (y < lineH * 0.7f) posPx = Math.min(maxPos(), (back() + 1) * lineH);
        else if (y > getHeight() - lineH * 0.7f) posPx = Math.max(0, (back() - 1) * lineH);

        float ty = dragFromLongPress ? y : y - lineH * 0.9f;   // the finger sits below the handle
        float tx = dragFromLongPress ? x : (dragHandle == 1 ? x + charW / 2 : x - charW / 2);
        long[] rc = cellAt(tx, ty);
        long id = rc[0];
        int col = Math.max(0, Math.min(cols - 1, (int) Math.floor(tx / charW)));

        if (dragHandle == 1) { selR1 = id; selC1 = col; } else { selR2 = id; selC2 = col; }
        if (selR1 > selR2 || (selR1 == selR2 && selC1 > selC2)) {      // handles crossed
            long t = selR1; selR1 = selR2; selR2 = t;
            int u = selC1; selC1 = selC2; selC2 = u;
            dragHandle = 3 - dragHandle;
        }
        invalidate();
        if (actionMode != null) actionMode.invalidateContentRect();
    }

    private void selectAll() {
        selR1 = emu.getDroppedLines(); selC1 = 0;
        selR2 = emu.getDroppedLines() + emu.getScrollbackSize() + emu.getRows() - 1;
        selC2 = Math.max(0, emu.getCols() - 1);
        hasSelection = true;
        invalidate();
        if (actionMode != null) actionMode.invalidateContentRect();
    }

    private void clearSelection() {
        hasSelection = false;
        dragHandle = 0;
        frozen = false;
        if (freezeFromLive) { freezeFromLive = false; posPx = 0; }   // back to following the output
        ActionMode m = actionMode;
        actionMode = null;           // set first so onDestroyActionMode knows we initiated this
        if (m != null) m.finish();
        invalidate();
    }

    private void showSelectionToolbar() {
        if (!hasSelection) return;
        if (actionMode != null) { actionMode.invalidateContentRect(); return; }
        actionMode = startActionMode(new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, 1, 1, "Copy");
                menu.add(Menu.NONE, 2, 2, "Paste");
                menu.add(Menu.NONE, 3, 3, "Select all");
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                    case 1: copySelection(); clearSelection(); return true;
                    case 2: clearSelection(); paste(); return true;
                    case 3: selectAll(); return true;
                    default: return false;
                }
            }
            @Override public void onDestroyActionMode(ActionMode mode) {
                if (actionMode == mode) { actionMode = null; clearSelection(); }
            }
            @Override public void onGetContentRect(ActionMode mode, View view, Rect out) {
                int sb = emu.getScrollbackSize();
                long drop = emu.getDroppedLines();
                int y1 = (int) (((selR1 - drop) - (sb - back())) * lineH + frac());
                int y2 = (int) (((selR2 - drop) - (sb - back()) + 1) * lineH + frac());
                y1 = Math.max(0, Math.min(getHeight(), y1));
                y2 = Math.max(y1 + (int) lineH, Math.min(getHeight(), y2));
                boolean oneRow = selR1 == selR2;
                out.set(oneRow ? (int) (selC1 * charW) : 0, y1, oneRow ? (int) ((selC2 + 1) * charW) : getWidth(), y2);
            }
        }, ActionMode.TYPE_FLOATING);

        if (actionMode == null) {          // no toolbar available: copy right away
            copySelection();
            clearSelection();
        }
    }

    private void copySelection() {
        if (emu == null || !hasSelection) return;
        String t = emu.getText(toAbs(selR1), selC1, toAbs(selR2), selC2);
        if (t.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", t));
            Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    public void paste() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
        if (t == null || t.length() == 0) return;
        pasteText(t.toString());
    }

    /** Types text as a paste: bracketed when the app supports it, so multi-line text is not run line by line. */
    public void pasteText(String text) {
        if (text == null || text.isEmpty()) return;
        String s = text.replace("\r\n", "\r").replace('\n', '\r');
        if (emu != null && emu.isBracketedPaste()) s = "\033[200~" + s + "\033[201~";
        sendRaw(s);
    }

    /** Currently selected terminal text, or "" when nothing is selected. */
    public String getSelectionText() {
        if (emu == null || !hasSelection) return "";
        return emu.getText(toAbs(selR1), selC1, toAbs(selR2), selC2);
    }

    public boolean isCtrlSticky() { return ctrlSticky; }
    public boolean isAltSticky() { return altSticky; }

    // ------------------------------------------------------------------ keyboard

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private String composing = "";

            private void apply(String t) {
                int common = 0;
                int m = Math.min(composing.length(), t.length());
                while (common < m && composing.charAt(common) == t.charAt(common)) common++;
                StringBuilder out = new StringBuilder();
                for (int i = common; i < composing.length(); i++) out.append('\u007f');
                out.append(t, common, t.length());
                composing = t;
                if (out.length() > 0) sendText(out.toString());
            }

            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                apply(text.toString());
                composing = "";
                return true;
            }
            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                apply(text.toString());
                return true;
            }
            @Override public boolean finishComposingText() { composing = ""; return true; }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (!composing.isEmpty()) {
                    composing = composing.substring(0, Math.max(0, composing.length() - before));
                }
                for (int i = 0; i < before; i++) sendRaw("\u007f");
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent e) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) handleKey(e.getKeyCode(), e);
                return true;
            }
        };
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (handleKey(keyCode, e)) return true;
        return super.onKeyDown(keyCode, e);
    }

    public void toggleCtrl() { ctrlSticky = !ctrlSticky; notifyMods(); }
    public void toggleAlt() { altSticky = !altSticky; notifyMods(); }
    private void notifyMods() { if (modListener != null) modListener.onModifiersChanged(ctrlSticky, altSticky); }

    /** Used by the extra-keys row (ev == null) and by real key events. */
    public boolean handleKey(int keyCode, KeyEvent ev) {
        boolean ctrl = ctrlSticky || (ev != null && ev.isCtrlPressed());
        boolean alt = altSticky || (ev != null && ev.isAltPressed());
        boolean shift = ev != null && ev.isShiftPressed();
        boolean app = emu != null && emu.isAppCursorKeys();
        String seq = null;
        int mod = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: seq = "\r"; break;
            case KeyEvent.KEYCODE_DEL: seq = "\u007f"; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: seq = "\033[3~"; break;
            case KeyEvent.KEYCODE_TAB: seq = shift ? "\033[Z" : "\t"; break;
            case KeyEvent.KEYCODE_ESCAPE: seq = "\033"; break;
            case KeyEvent.KEYCODE_DPAD_UP: seq = arrow('A', mod, app); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: seq = arrow('B', mod, app); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seq = arrow('C', mod, app); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: seq = arrow('D', mod, app); break;
            case KeyEvent.KEYCODE_MOVE_HOME: seq = arrow('H', mod, app); break;
            case KeyEvent.KEYCODE_MOVE_END: seq = arrow('F', mod, app); break;
            case KeyEvent.KEYCODE_PAGE_UP: seq = "\033[5~"; break;
            case KeyEvent.KEYCODE_PAGE_DOWN: seq = "\033[6~"; break;
            case KeyEvent.KEYCODE_INSERT: seq = "\033[2~"; break;
            case KeyEvent.KEYCODE_F1: seq = "\033OP"; break;
            case KeyEvent.KEYCODE_F2: seq = "\033OQ"; break;
            case KeyEvent.KEYCODE_F3: seq = "\033OR"; break;
            case KeyEvent.KEYCODE_F4: seq = "\033OS"; break;
            case KeyEvent.KEYCODE_F5: seq = "\033[15~"; break;
            case KeyEvent.KEYCODE_F6: seq = "\033[17~"; break;
            case KeyEvent.KEYCODE_F7: seq = "\033[18~"; break;
            case KeyEvent.KEYCODE_F8: seq = "\033[19~"; break;
            case KeyEvent.KEYCODE_F9: seq = "\033[20~"; break;
            case KeyEvent.KEYCODE_F10: seq = "\033[21~"; break;
            case KeyEvent.KEYCODE_F11: seq = "\033[23~"; break;
            case KeyEvent.KEYCODE_F12: seq = "\033[24~"; break;
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT: case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK: case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_HOME:
                return false;
            default: break;
        }

        if (seq == null) {
            if (ev == null) return false;
            int meta = ev.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK);
            int uc = ev.getUnicodeChar(meta);
            if ((uc & KeyCharacterMap.COMBINING_ACCENT) != 0) return true;
            if (uc == 0) return false;
            seq = new String(Character.toChars(uc));
            sendText(seq);   // applies sticky ctrl/alt itself
            return true;
        }
        if (alt && seq.length() == 1) seq = "\033" + seq;
        sendRaw(seq);
        clearSticky();
        return true;
    }

    private static String arrow(char c, int mod, boolean app) {
        if (mod > 1) return "\033[1;" + mod + c;
        return (app ? "\033O" : "\033[") + c;
    }

    /** Text typed on a soft keyboard: apply sticky Ctrl/Alt per character. */
    private void sendText(String s) {
        boolean ctrl = ctrlSticky, alt = altSticky;
        if (!ctrl && !alt) { sendRaw(s.replace('\n', '\r')); return; }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (alt) out.append('\033');
            if (ctrl) out.append((char) ctrlChar(cp)); else out.appendCodePoint(cp);
        }
        sendRaw(out.toString());
        clearSticky();
    }

    private static int ctrlChar(int c) {
        if (c >= 'a' && c <= 'z') return c - 'a' + 1;
        if (c >= 'A' && c <= 'Z') return c - 'A' + 1;
        switch (c) {
            case ' ': case '@': case '2': return 0;
            case '[': case '3': return 27;
            case '\\': case '4': return 28;
            case ']': case '5': return 29;
            case '^': case '6': return 30;
            case '_': case '7': return 31;
            case '?': return 127;
            default: return c;
        }
    }

    private void clearSticky() {
        if (ctrlSticky || altSticky) { ctrlSticky = altSticky = false; notifyMods(); }
    }

    private void sendRaw(String s) {
        if (session == null) return;
        posPx = 0;
        scrollAccum = 0;
        frozen = false;
        freezeFromLive = false;
        scroller.forceFinished(true);
        if (hasSelection) clearSelection();
        session.write(s.getBytes(StandardCharsets.UTF_8));
        invalidate();
    }

    private void sendArrow(char c) {
        sendRaw((emu != null && emu.isAppCursorKeys() ? "\033O" : "\033[") + c);
    }

    /** xterm mouse report. button: 0 left, 64 wheel up, 65 wheel down. col/row are 0-based screen cells. */
    private void sendMouse(int button, int col, int row, boolean press) {
        if (emu == null) return;
        if (emu.isMouseSgr()) {
            sendRaw("\033[<" + button + ";" + (col + 1) + ";" + (row + 1) + (press ? "M" : "m"));
        } else if (col < 95 && row < 95) {                 // legacy encoding only addresses 223 cells; stay ASCII-safe
            int b = press ? button : 3;
            sendRaw("\033[M" + (char) (32 + b) + (char) (32 + col + 1) + (char) (32 + row + 1));
        }
    }

    /** Called by extra-keys row for literal text such as "-", "/", "|". */
    public void typeText(String s) { sendText(s); }
}
