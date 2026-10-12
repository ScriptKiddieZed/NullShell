package com.zed.nullshell;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies LSP-style edits (line / UTF-16 column ranges) to a string. Pure Java so it can be tested
 * without lsp4j; {@link LspManager} adapts WorkspaceEdit / TextEdit to these arrays.
 */
final class TextEditApply {
    private TextEditApply() { }

    /** One edit: replace [startLine:startCol, endLine:endCol) with text. */
    static final class Edit {
        final int startLine, startCol, endLine, endCol;
        final String text;
        Edit(int sl, int sc, int el, int ec, String text) {
            startLine = sl; startCol = sc; endLine = el; endCol = ec; this.text = text == null ? "" : text;
        }
    }

    /** Offsets of the first character of every line (handles \n, \r\n and lone \r like the editor does). */
    static int[] lineStarts(String s) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') starts.add(i + 1);
            else if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    /** Offset of a line/column, clamped into the text (servers occasionally send past-the-end positions). */
    static int offset(String s, int[] starts, int line, int col) {
        if (line < 0) return 0;
        if (line >= starts.length) return s.length();
        int lineEnd = line + 1 < starts.length ? starts[line + 1] : s.length();
        // column may not go past the line's content (before its terminator)
        int contentEnd = lineEnd;
        while (contentEnd > starts[line] && (s.charAt(contentEnd - 1) == '\n' || s.charAt(contentEnd - 1) == '\r')) contentEnd--;
        return Math.max(starts[line], Math.min(starts[line] + Math.max(0, col), contentEnd));
    }

    /** Applies all edits against the original positions, later ones first, so earlier ranges stay valid. */
    static String apply(String text, List<Edit> edits) {
        if (edits.isEmpty()) return text;
        int[] starts = lineStarts(text);
        List<int[]> spans = new ArrayList<>();      // {start, end, index}
        for (int i = 0; i < edits.size(); i++) {
            Edit e = edits.get(i);
            int a = offset(text, starts, e.startLine, e.startCol);
            int b = offset(text, starts, e.endLine, e.endCol);
            if (b < a) { int t = a; a = b; b = t; }
            spans.add(new int[]{a, b, i});
        }
        spans.sort((x, y) -> x[0] != y[0] ? Integer.compare(y[0], x[0]) : Integer.compare(y[2], x[2]));
        StringBuilder sb = new StringBuilder(text);
        int lastStart = Integer.MAX_VALUE;
        for (int[] sp : spans) {
            if (sp[1] > lastStart) continue;            // overlapping edit: skip, the server sent something inconsistent
            sb.replace(sp[0], sp[1], edits.get(sp[2]).text);
            lastStart = sp[0];
        }
        return sb.toString();
    }
}
