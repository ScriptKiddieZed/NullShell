package com.zed.nullshell;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns rendered snippet text into terminal input. Newlines become Enter; a small set of
 * &lt;tokens&gt; sends keys or pauses. Anything else with angle brackets (redirections, heredocs)
 * is typed as-is. Pure Java for desktop testing.
 *
 * Tokens: &lt;Enter&gt; &lt;Tab&gt; &lt;Esc&gt; &lt;BS&gt; &lt;Up&gt; &lt;Down&gt; &lt;Left&gt; &lt;Right&gt; &lt;Home&gt; &lt;End&gt;
 * &lt;C-a&gt;..&lt;C-z&gt; &lt;sleep 500&gt; (ms) &lt;lt&gt; (a literal &lt;)
 */
public final class KeyTokens {
    private KeyTokens() { }

    public static final class Action {
        public final byte[] data;     // null for a pause
        public final long sleepMs;
        Action(byte[] d, long s) { data = d; sleepMs = s; }
    }

    private static final Pattern TOKEN = Pattern.compile(
            "<(?i:(enter|tab|esc|bs|up|down|left|right|home|end|lt)|c-([a-z])|sleep\\s+(\\d{1,6}))>");

    public static List<Action> parse(String text, boolean enterAtEnd) {
        List<Action> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Matcher m = TOKEN.matcher(text);
        int pos = 0;
        while (m.find()) {
            buf.append(text, pos, m.start());
            pos = m.end();
            flush(buf, out);
            if (m.group(1) != null) {
                String t = m.group(1).toLowerCase();
                switch (t) {
                    case "enter": out.add(bytes("\r")); break;
                    case "tab": out.add(bytes("\t")); break;
                    case "esc": out.add(bytes("\033")); break;
                    case "bs": out.add(bytes("\u007f")); break;
                    case "up": out.add(bytes("\033[A")); break;
                    case "down": out.add(bytes("\033[B")); break;
                    case "right": out.add(bytes("\033[C")); break;
                    case "left": out.add(bytes("\033[D")); break;
                    case "home": out.add(bytes("\033[H")); break;
                    case "end": out.add(bytes("\033[F")); break;
                    default: buf.append('<'); break;               // <lt>
                }
            } else if (m.group(2) != null) {
                out.add(bytes(String.valueOf((char) (Character.toLowerCase(m.group(2).charAt(0)) - 'a' + 1))));
            } else {
                out.add(new Action(null, Long.parseLong(m.group(3))));
            }
        }
        buf.append(text.substring(pos));
        flush(buf, out);
        if (enterAtEnd && !endsWithEnter(out)) out.add(bytes("\r"));
        return out;
    }

    /** Text with the key tokens removed, for "insert without running". */
    public static String stripTokens(String text) {
        return TOKEN.matcher(text).replaceAll(m -> "lt".equalsIgnoreCase(m.group(1)) ? "<" : "");
    }

    private static boolean endsWithEnter(List<Action> a) {
        if (a.isEmpty()) return false;
        Action last = a.get(a.size() - 1);
        return last.data != null && last.data.length == 1 && last.data[0] == '\r';
    }

    private static void flush(StringBuilder buf, List<Action> out) {
        if (buf.length() == 0) return;
        String s = buf.toString().replace("\r\n", "\n");
        buf.setLength(0);
        int start = 0;
        for (int i = 0; i < s.length(); i++) {          // split at newlines so the runner can pace the lines
            if (s.charAt(i) == '\n') {
                if (i > start) out.add(bytes(s.substring(start, i)));
                out.add(bytes("\r"));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(bytes(s.substring(start)));
    }

    private static Action bytes(String s) { return new Action(s.getBytes(StandardCharsets.UTF_8), 0); }
}
