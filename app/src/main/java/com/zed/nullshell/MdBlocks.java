package com.zed.nullshell;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a chat reply into blocks: normal text, fenced code, and tables. Pure Java so it can be tested on a desktop JVM.
 * Works on partial text too (a reply that is still streaming): an unfinished code fence becomes a code block that is
 * not yet closed.
 */
final class MdBlocks {
    private MdBlocks() { }

    static final int TEXT = 0, CODE = 1, TABLE = 2;

    static final class Block {
        final int kind;
        final String lang;     // CODE only, may be ""
        final String text;     // TEXT: markdown source; CODE: the code only; TABLE: raw rows joined by \n
        final boolean closed;  // CODE: the closing fence has arrived
        Block(int kind, String lang, String text, boolean closed) { this.kind = kind; this.lang = lang; this.text = text; this.closed = closed; }
    }

    static List<Block> split(String src) {
        List<Block> out = new ArrayList<>();
        if (src == null || src.isEmpty()) return out;
        String[] lines = src.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                flushText(out, buf);
                String fence = t.startsWith("```") ? "```" : "~~~";
                String lang = t.substring(3).trim();
                int sp = lang.indexOf(' ');
                if (sp > 0) lang = lang.substring(0, sp);
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                int n = 0;
                while (j < lines.length) {
                    if (lines[j].trim().startsWith(fence) && lines[j].trim().replace(fence.charAt(0), ' ').trim().isEmpty()) { closed = true; break; }
                    if (n++ > 0) code.append('\n');
                    code.append(lines[j]);
                    j++;
                }
                out.add(new Block(CODE, lang, code.toString(), closed));
                i = closed ? j + 1 : j;
                continue;
            }
            if (isTableRow(t) && i + 1 < lines.length && isTableSeparator(lines[i + 1].trim())) {
                flushText(out, buf);
                StringBuilder tb = new StringBuilder();
                int j = i;
                while (j < lines.length && isTableRow(lines[j].trim())) {
                    if (j > i) tb.append('\n');
                    tb.append(lines[j].trim());
                    j++;
                }
                out.add(new Block(TABLE, "", tb.toString(), true));
                i = j;
                continue;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(line);
            i++;
        }
        flushText(out, buf);
        return out;
    }

    private static void flushText(List<Block> out, StringBuilder buf) {
        String s = buf.toString();
        buf.setLength(0);
        // trim blank lines at the edges but keep inner structure
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == '\r')) a++;
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
        if (a < b) out.add(new Block(TEXT, "", s.substring(a, b), true));
    }

    private static boolean isTableRow(String t) { return t.startsWith("|") && t.indexOf('|', 1) > 0; }

    private static boolean isTableSeparator(String t) {
        if (!isTableRow(t)) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return t.indexOf('-') >= 0;
    }

    /** Table rows as cells; the separator row is dropped. */
    static List<String[]> tableRows(String raw) {
        List<String[]> rows = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (isTableSeparator(t)) continue;
            if (t.startsWith("|")) t = t.substring(1);
            if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
            String[] cells = t.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
            rows.add(cells);
        }
        return rows;
    }

    /** The table as aligned monospace text. */
    static String tableText(String raw) {
        List<String[]> rows = tableRows(raw);
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        int[] w = new int[cols];
        for (String[] r : rows) for (int i = 0; i < r.length; i++) w[i] = Math.max(w[i], plain(r[i]).length());
        StringBuilder sb = new StringBuilder();
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            for (int i = 0; i < cols; i++) {
                String c = i < r.length ? plain(r[i]) : "";
                sb.append(c);
                for (int k = c.length(); k < w[i]; k++) sb.append(' ');
                if (i < cols - 1) sb.append("  │ ");
            }
            sb.append('\n');
            if (ri == 0) {
                for (int i = 0; i < cols; i++) {
                    for (int k = 0; k < w[i]; k++) sb.append('─');
                    if (i < cols - 1) sb.append("──┼─");
                }
                sb.append('\n');
            }
        }
        return sb.toString().replaceAll("\\s+$", "");
    }

    /** Markdown with the markers removed (for "Copy as plain text"). Code stays as it is. */
    static String plain(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("`([^`]+)`", "$1").replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "$1");
    }

    /** Whole reply as plain text: markers dropped, code blocks kept, headings and bullets simplified. */
    static String toPlain(String md) {
        StringBuilder sb = new StringBuilder();
        for (Block b : split(md)) {
            if (sb.length() > 0) sb.append("\n\n");
            if (b.kind == CODE) sb.append(b.text);
            else if (b.kind == TABLE) sb.append(tableText(b.text));
            else {
                for (String line : b.text.split("\n")) {
                    String l = line.replaceFirst("^\\s{0,3}#{1,6}\\s+", "").replaceFirst("^(\\s*)[-*+]\\s+", "$1• ");
                    sb.append(plain(l)).append('\n');
                }
                sb.setLength(sb.length() - 1);
            }
        }
        return sb.toString();
    }
}
