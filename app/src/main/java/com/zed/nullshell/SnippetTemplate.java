package com.zed.nullshell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command written as plain text with {placeholders}. Pure Java (no Android imports) so it can be
 * unit-tested on a desktop JVM.
 *
 * <pre>
 *   {target}            required text           {port=80}           text with default
 *   {ports?}            optional text           {ports?-p}          optional text with prefix ("-p 1-1000")
 *   {verbose:-v}        toggle (adds -v)        {timing=|-T3|-T4}   choice (first option is the default)
 *   {Service version:-sV}  names may contain spaces; the name is the label shown in the form
 *   {!extra?}           raw (never quoted)      {clipboard} {selection} {date} {datetime}   built-ins
 *   {each: -a {mode=fast|deep} {target}}        repeating group; rows are added in the form
 *   {{ and }}           literal braces          ${HOME}, awk '{print $1}', find -exec {}   stay literal
 *
 *   Reuse + filters     {target|host}  {url|host|upper}  {file|stem}.bak
 *                       slug upper lower trim host port path scheme dir name stem ext urlencode
 *   Derived defaults    {out=scan_{target|slug}.txt}      follows the other fields until it is edited
 *   Secrets             {pass*}  {token*?--token}         masked, never saved
 *   Multi-select        {ports+,:22|80|443}               ticks joined with ","  (*opt = ticked by default)
 *                       {scripts+,?--script=:vuln|safe}   with a prefix when anything is ticked
 *   Conditions          {if deep: -A --script vuln}  {if mode=fast: -T4 |else: -T2}
 *                       tests: name  !name  name=value  name!=value  a && b  a || b
 * </pre>
 */
public final class SnippetTemplate {
    public static final int TEXT = 0, TOGGLE = 1, CHOICE = 2, MULTI = 3;
    public static final int PICK_NONE = 0, PICK_FILE = 1, PICK_DIR = 2;

    public static final class Field {
        public String name;
        public int kind;
        public boolean optional, raw;
        public String prefix = "";
        public String flag = "";
        /** Default text; for a derived default this is the template text, see {@link #dynamic()}. For MULTI: ticked options joined by "\n". */
        public String def = "";
        public List<String> options = new ArrayList<>();
        /** Browse button: PICK_FILE / PICK_DIR (explicit {name@file}, {name@dir}, or guessed from the name). */
        public int pick;
        /** Only shown (and only used in the command) while the named toggle is on / off. */
        public String showIf;
        public boolean showIfNot;
        /** Password-style field: masked in the form and preview, never saved. */
        public boolean secret;
        /** MULTI: text put between the ticked options. */
        public String sep = " ";

        private List<Node> defNodes;
        private Guards guards = new Guards();

        /** The name as written, with underscores shown as spaces and the first letter capitalised. */
        public String label() {
            String s = name.replace('_', ' ');
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        public boolean required() { return kind == TEXT && !optional && def.isEmpty(); }
        /** True when the default is built from other fields (e.g. scan_{target|slug}.txt). */
        public boolean dynamic() { return defNodes != null; }

        private boolean weak() { return kind == TEXT && def.isEmpty() && defNodes == null && !secret; }
        private void takeDefinition(Field c) {
            kind = c.kind; def = c.def; defNodes = c.defNodes; options = c.options; flag = c.flag; sep = c.sep; secret = c.secret;
        }
    }

    public static final class Group {
        public final String id;
        public final List<Field> fields = new ArrayList<>();
        Group(String id) { this.id = id; }
    }

    // ------------------------------------------------------------------ nodes

    private abstract static class Node { }
    private static final class Lit extends Node { final String text; Lit(String t) { text = t; } }
    private static final class Fld extends Node { final Field f; Fld(Field f) { this.f = f; } }
    private static final class Ref extends Node {
        final String name; final List<String> filters; final boolean raw;
        Ref(String n, List<String> fl, boolean r) { name = n; filters = fl; raw = r; }
    }
    private static final class Builtin extends Node { final String name; final boolean raw; Builtin(String n, boolean r) { name = n; raw = r; } }
    private static final class Each extends Node { final String id; final List<Node> body; Each(String id, List<Node> b) { this.id = id; body = b; } }
    private static final class If extends Node {
        final Cond cond; final List<Node> then, els;
        If(Cond c, List<Node> t, List<Node> e) { cond = c; then = t; els = e; }
    }

    private static final class Guard {
        final Cond c; final boolean neg;
        Guard(Cond c, boolean neg) { this.c = c; this.neg = neg; }
    }
    /** Where a field appears: one chain of conditions per occurrence; any unguarded occurrence makes it always visible. */
    private static final class Guards {
        final List<List<Guard>> chains = new ArrayList<>();
        boolean open;
        void add(List<Guard> chain) { if (chain.isEmpty()) open = true; else chains.add(chain); }
    }

    private interface Lookup { String get(String name); }

    /** name | !name | name=value | name!=value | a && b | a || b */
    private static final class Cond {
        static final int OR = 0, AND = 1, NOT = 2, TRUTHY = 3, EQ = 4, NE = 5;
        final int op; final Cond a, b; final String name, val;
        Cond(int op, Cond a, Cond b, String name, String val) { this.op = op; this.a = a; this.b = b; this.name = name; this.val = val; }

        boolean eval(Lookup l) {
            switch (op) {
                case OR: return a.eval(l) || b.eval(l);
                case AND: return a.eval(l) && b.eval(l);
                case NOT: return !a.eval(l);
                case TRUTHY: {
                    String v = l.get(name);
                    return v != null && !v.trim().isEmpty() && !"false".equals(v.trim());
                }
                default: {
                    String v = l.get(name);
                    boolean has = false;
                    if (v != null) for (String p : v.split("\n", -1)) if (p.trim().equals(val)) { has = true; break; }
                    return op == EQ ? has : !has;
                }
            }
        }
        void names(Collection<String> out) {
            if (a != null) a.names(out);
            if (b != null) b.names(out);
            if (name != null) out.add(name);
        }
        boolean mentions(String n) { Set<String> s = new LinkedHashSet<>(); names(s); return s.contains(n); }
    }

    private static final Pattern SAFE = Pattern.compile("^~?[A-Za-z0-9_@%+=:,./-]*$");
    private static final List<String> BUILTINS = Arrays.asList("clipboard", "selection", "date", "datetime");
    private static final List<String> FILTERS = Arrays.asList("slug", "upper", "lower", "trim", "host", "port", "path", "scheme", "dir", "name", "stem", "ext", "urlencode");

    private final List<Node> nodes;
    private final List<Field> topFields = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Field> byName = new LinkedHashMap<>();
    private final List<Field> secrets = new ArrayList<>();
    private boolean hasLogic;
    private int groupCounter;
    private int depth;

    private SnippetTemplate(String src) {
        nodes = parse(src, false);
        collect(nodes, topFields, new ArrayList<>());
        unify(nodes, topFields);
        List<Each> eaches = new ArrayList<>();
        findEach(nodes, eaches);
        for (Each e : eaches) {
            Group g = new Group(e.id);
            collect(e.body, g.fields, new ArrayList<>());
            unify(e.body, g.fields);
            groups.add(g);
        }
        for (Field f : topFields) { byName.put(f.name, f); if (f.secret) secrets.add(f); }
        for (Group g : groups) for (Field f : g.fields) if (!byName.containsKey(f.name)) byName.put(f.name, f);
        hasLogic = containsIf(nodes);
    }

    public static SnippetTemplate parse(String src) { return new SnippetTemplate(src == null ? "" : src); }

    public static List<String> filterNames() { return FILTERS; }

    public List<Field> fields() { return topFields; }
    public List<Group> groups() { return groups; }
    public boolean hasFields() { return !topFields.isEmpty() || !groups.isEmpty() || hasLogic; }
    /** Secret (masked, never saved) fields, in order. */
    public List<Field> secrets() { return secrets; }

    /** Things the author probably got wrong, e.g. a condition that names a field that does not exist. */
    public List<String> problems() {
        Set<String> used = new LinkedHashSet<>();
        condNames(nodes, used);
        List<String> out = new ArrayList<>();
        for (String n : used) if (!byName.containsKey(n) && !BUILTINS.contains(n)) out.add("{if} uses “" + n + "”, which is not a field in this command");
        return out;
    }

    private static void condNames(List<Node> list, Set<String> out) {
        for (Node n : list) {
            if (n instanceof If) { If i = (If) n; i.cond.names(out); condNames(i.then, out); condNames(i.els, out); }
            else if (n instanceof Each) condNames(((Each) n).body, out);
            else if (n instanceof Fld && ((Fld) n).f.defNodes != null) condNames(((Fld) n).f.defNodes, out);
        }
    }

    private static boolean containsIf(List<Node> list) {
        for (Node n : list) {
            if (n instanceof If) return true;
            if (n instanceof Each && containsIf(((Each) n).body)) return true;
        }
        return false;
    }

    private static void findEach(List<Node> list, List<Each> out) {
        for (Node n : list) {
            if (n instanceof Each) out.add((Each) n);
            else if (n instanceof If) { findEach(((If) n).then, out); findEach(((If) n).els, out); }
        }
    }

    // ------------------------------------------------------------------ parsing

    private List<Node> parse(String s, boolean inEach) {
        List<Node> out = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            char next = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '$' && next == '{') {                       // shell ${VAR}: copy through the matching brace
                int end = matchBrace(s, i + 1);
                int stop = end < 0 ? n - 1 : end;
                lit.append(s, i, stop + 1);
                i = stop + 1;
            } else if (c == '{' && next == '{') { lit.append('{'); i += 2; }
            else if (c == '}' && next == '}') { lit.append('}'); i += 2; }
            else if (c == '{') {
                int end = matchBrace(s, i);
                if (end < 0) { lit.append(c); i++; continue; }
                String inner = s.substring(i + 1, end);
                Node node = parsePlaceholder(inner, inEach);
                if (node == null) {
                    lit.append(s, i, end + 1);                    // not ours (awk, find -exec {} ...)
                } else {
                    if (lit.length() > 0) { out.add(new Lit(lit.toString())); lit.setLength(0); }
                    out.add(node);
                }
                i = end + 1;
            } else { lit.append(c); i++; }
        }
        if (lit.length() > 0) out.add(new Lit(lit.toString()));
        return out;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** Splits on a separator that is not inside nested braces. */
    private static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == sep && depth <= 0) { out.add(s.substring(from, i)); from = i + 1; }
        }
        out.add(s.substring(from));
        return out;
    }

    private static final Pattern IF_HEAD = Pattern.compile("^if\\s+.*", Pattern.DOTALL);

    private Node parseIf(String inner, boolean inEach) {
        String rest = inner.substring(2).trim();
        int colon = -1;
        char q = 0;
        for (int i = 0; i < rest.length(); i++) {                // first ':' outside quotes
            char c = rest.charAt(i);
            if (q != 0) { if (c == q) q = 0; }
            else if (c == '\'' || c == '"') q = c;
            else if (c == ':') { colon = i; break; }
        }
        if (colon < 0) return null;
        Cond cond = parseCond(rest.substring(0, colon));
        if (cond == null) return null;
        String body = rest.substring(colon + 1), elseBody = null;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {                // "|else:" at the top level of the block
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '|' && depth == 0) {
                int j = i + 1;
                while (j < body.length() && body.charAt(j) == ' ') j++;
                if (body.startsWith("else:", j)) { elseBody = body.substring(j + 5); body = body.substring(0, i); break; }
            }
        }
        List<Node> then = parse(body.trim(), inEach);
        List<Node> els = elseBody == null ? new ArrayList<>() : parse(elseBody.trim(), inEach);
        return new If(cond, then, els);
    }

    private static List<String> splitOp(String s, String op) {
        List<String> out = new ArrayList<>();
        int from = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (s.startsWith(op, i)) { out.add(s.substring(from, i)); from = i + op.length(); i += op.length() - 1; }
        }
        out.add(s.substring(from));
        return out;
    }

    private static Cond parseCond(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        List<String> or = splitOp(s, "||");
        if (or.size() > 1) {
            Cond c = null;
            for (String p : or) { Cond x = parseCond(p); if (x == null) return null; c = c == null ? x : new Cond(Cond.OR, c, x, null, null); }
            return c;
        }
        List<String> and = splitOp(s, "&&");
        if (and.size() > 1) {
            Cond c = null;
            for (String p : and) { Cond x = parseCond(p); if (x == null) return null; c = c == null ? x : new Cond(Cond.AND, c, x, null, null); }
            return c;
        }
        if (s.startsWith("!") && !s.startsWith("!=")) {
            Cond x = parseCond(s.substring(1));
            return x == null ? null : new Cond(Cond.NOT, x, null, null, null);
        }
        int op = -1, at = -1;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == '!' && i + 1 < s.length() && s.charAt(i + 1) == '=') { op = Cond.NE; at = i; break; }
            if (c == '=') { op = Cond.EQ; at = i; break; }
        }
        if (op < 0) return validName(s) ? new Cond(Cond.TRUTHY, null, null, s, null) : null;
        String name = s.substring(0, at).trim();
        int vs = at + (op == Cond.NE ? 2 : 1);
        if (op == Cond.EQ && vs < s.length() && s.charAt(vs) == '=') vs++;      // "=="
        String val = s.substring(vs).trim();
        if (val.length() >= 2 && (val.charAt(0) == '\'' || val.charAt(0) == '"') && val.charAt(val.length() - 1) == val.charAt(0))
            val = val.substring(1, val.length() - 1);
        return validName(name) ? new Cond(op, null, null, name, val) : null;
    }

    private static boolean validName(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == ' ')) return false;
        }
        return true;
    }

    private Node parsePlaceholder(String inner, boolean inEach) {
        if (inner.startsWith("each:") && !inEach) {
            return new Each("g" + (groupCounter++), parse(inner.substring(5).trim(), true));
        }
        if (IF_HEAD.matcher(inner).matches()) {
            Node n = parseIf(inner, inEach);
            if (n != null) return n;
        }
        String condName = null;
        boolean condNot = false;
        Matcher cm = SHOW_IF.matcher(inner);                          // {name ... @if Toggle name}
        if (cm.matches()) { inner = cm.group(1); condNot = cm.group(2).equals("ifnot"); condName = cm.group(3).trim(); }
        // optional "!" (raw), then a name made of letters, digits, "_" and spaces (the name doubles as the
        // label shown in the form), then optionally ? : = * | + or @ followed by the modifier text
        int i = 0;
        boolean raw = false;
        if (inner.startsWith("!")) { raw = true; i = 1; }
        int start = i;
        if (i >= inner.length() || !(Character.isLetter(inner.charAt(i)) || inner.charAt(i) == '_')) return null;
        while (i < inner.length() && (Character.isLetterOrDigit(inner.charAt(i)) || inner.charAt(i) == '_' || inner.charAt(i) == ' ')) i++;
        String name = inner.substring(start, i).trim();
        String rest = inner.substring(i);

        boolean secret = false;
        if (rest.startsWith("*")) { secret = !inEach; rest = rest.substring(1); }      // top level only: rows are not masked

        int pickHint = -1;                                        // {name@file} {name@dir} {name@none}
        if (rest.startsWith("@")) {
            Matcher pm = PICK_HINT.matcher(rest);
            if (!pm.matches()) return null;                       // e.g. {user@host}
            String h = pm.group(1);
            pickHint = h.equals("file") ? PICK_FILE : h.equals("dir") ? PICK_DIR : PICK_NONE;
            rest = pm.group(2) == null ? "" : pm.group(2);
        }

        if (rest.startsWith("|") && !secret) {                    // {name|filter|filter}: another use of a field
            List<String> fl = new ArrayList<>();
            for (String p : rest.substring(1).split("\\|", -1)) {
                p = p.trim().toLowerCase(Locale.ROOT);
                if (!FILTERS.contains(p)) return null;
                fl.add(p);
            }
            return new Ref(name, fl, raw);
        }

        if (BUILTINS.contains(name) && rest.isEmpty() && !secret) return new Builtin(name, raw);

        Field f = new Field();
        f.name = name;
        f.raw = raw;
        f.secret = secret;
        if (rest.isEmpty()) { f.kind = TEXT; }
        else if (rest.startsWith("?")) { f.kind = TEXT; f.optional = true; f.prefix = rest.substring(1).trim(); }
        else if (rest.startsWith(":") && !secret) { f.kind = TOGGLE; f.flag = rest.substring(1).trim(); }
        else if (rest.startsWith("+") && !secret) {
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            String head = rest.substring(1, colon);
            String sep = head;
            int q = head.indexOf('?');
            if (q >= 0) { sep = head.substring(0, q); f.prefix = head.substring(q + 1).trim(); }
            f.kind = MULTI;
            f.optional = true;
            f.sep = sep.isEmpty() ? " " : sep;
            List<String> on = new ArrayList<>();
            for (String o : splitTop(rest.substring(colon + 1), '|')) {
                o = o.trim();
                boolean ticked = o.length() > 1 && o.startsWith("*");
                if (ticked) o = o.substring(1).trim();
                if (o.isEmpty()) continue;
                f.options.add(o);
                if (ticked) on.add(o);
            }
            if (f.options.isEmpty()) return null;
            f.def = String.join("\n", on);
        }
        else if (rest.startsWith("=") && !secret) {
            String v = rest.substring(1);
            List<String> parts = splitTop(v, '|');
            if (parts.size() > 1) {
                f.kind = CHOICE;
                for (String o : parts) f.options.add(o.trim());
            } else {
                f.kind = TEXT;
                f.def = v;
                if (v.indexOf('{') >= 0) {                        // derived default: scan_{target|slug}.txt
                    List<Node> dn = parse(v, true);
                    boolean allLit = true;
                    StringBuilder plain = new StringBuilder();
                    for (Node x : dn) { if (x instanceof Lit) plain.append(((Lit) x).text); else allLit = false; }
                    if (allLit) f.def = plain.toString(); else f.defNodes = dn;
                }
            }
        } else return null;                                       // e.g. {print $1}
        if (f.kind == TEXT) f.pick = pickHint >= 0 ? pickHint : guessPick(name);
        f.showIf = condName;
        f.showIfNot = condNot;
        return new Fld(f);
    }

    private static final Pattern SHOW_IF = Pattern.compile("^(.*?)\\s+@(if|ifnot)\\s+(.+?)\\s*$", Pattern.DOTALL);
    private static final Pattern PICK_HINT = Pattern.compile("^@(file|dir|none)([?:=].*)?$", Pattern.DOTALL);

    /** Fields whose name says "file" or "folder" get a Browse button without any extra syntax. */
    static int guessPick(String name) {
        for (String w : name.toLowerCase(Locale.US).split("[^a-z]+")) {
            switch (w) {
                case "file": case "files": case "infile": case "wordlist": case "pcap": case "path": case "script": case "keyfile":
                    return PICK_FILE;
                case "dir": case "directory": case "folder": case "outdir":
                    return PICK_DIR;
                default:
            }
        }
        return PICK_NONE;
    }

    // ------------------------------------------------------------------ field collection

    private static Field find(List<Field> into, String name) {
        for (Field e : into) if (e.name.equals(name)) return e;
        return null;
    }

    private static Field plain(String name) {
        Field f = new Field();
        f.name = name;
        f.kind = TEXT;
        f.pick = guessPick(name);
        return f;
    }

    private static boolean strong(Field f) { return f.kind != TEXT || !f.def.isEmpty() || f.defNodes != null || f.secret; }

    private void add(List<Field> into, Field f, List<Guard> chain) {
        Field e = find(into, f.name);
        if (e == null) { into.add(f); f.guards.add(chain); return; }
        if (e.weak() && strong(f)) e.takeDefinition(f);
        e.guards.add(chain);
    }

    private void collect(List<Node> list, List<Field> into, List<Guard> chain) {
        for (Node n : list) {
            if (n instanceof Fld) {
                Field f = ((Fld) n).f;
                add(into, f, chain);
                if (f.defNodes != null) collect(f.defNodes, into, chain);
            } else if (n instanceof Ref) {
                String nm = ((Ref) n).name;
                if (!BUILTINS.contains(nm)) add(into, plain(nm), chain);
            } else if (n instanceof If) {
                If i = (If) n;
                List<Guard> a = new ArrayList<>(chain); a.add(new Guard(i.cond, false));
                List<Guard> b = new ArrayList<>(chain); b.add(new Guard(i.cond, true));
                collect(i.then, into, a);
                collect(i.els, into, b);
            }
        }
    }

    /** Every use of a name shares the richest definition and the same visibility rule. */
    private void unify(List<Node> list, List<Field> scope) { unify(list, scope, new java.util.IdentityHashMap<List<Node>, Boolean>()); }

    private void unify(List<Node> list, List<Field> scope, java.util.IdentityHashMap<List<Node>, Boolean> seen) {
        if (seen.put(list, Boolean.TRUE) != null) return;           // a default that mentions itself
        for (Node n : list) {
            if (n instanceof Fld) {
                Field f = ((Fld) n).f;
                Field c = find(scope, f.name);
                if (c != null && c != f) {
                    if (f.weak()) f.takeDefinition(c);
                    if (f.pick == PICK_NONE) f.pick = c.pick;
                    f.guards = c.guards;
                }
                if (f.defNodes != null) unify(f.defNodes, scope, seen);
            } else if (n instanceof If) {
                unify(((If) n).then, scope, seen);
                unify(((If) n).els, scope, seen);
            }
        }
    }

    // ------------------------------------------------------------------ values & visibility

    private Lookup lookup(final Map<String, String> own, final Map<String, String> top, final Map<String, String> builtins) {
        return name -> resolve(name, own, top, builtins);
    }

    /** Current value of a field by name; falls back to its default, then to the built-ins. Null when unknown. */
    private String resolve(String name, Map<String, String> own, Map<String, String> top, Map<String, String> builtins) {
        String v = own == null ? null : own.get(name);
        if (v == null && top != null) v = top.get(name);
        if (v != null) return v;
        Field f = byName.get(name);
        if (f != null) {
            switch (f.kind) {
                case TOGGLE: return "false";
                case CHOICE: return f.options.isEmpty() ? "" : f.options.get(0);
                case MULTI: return f.def;
                default: return defaultFor(f, own, top, builtins);
            }
        }
        return builtins == null ? null : builtins.get(name);
    }

    /** The text a field starts with: its default, with other fields filled in when the default is derived. */
    public String defaultFor(Field f, Map<String, String> own, Map<String, String> top, Map<String, String> builtins) {
        if (f.defNodes == null) return f.def;
        if (depth > 5) return "";
        depth++;
        try {
            Map<String, String> t = top == null ? new LinkedHashMap<String, String>() : top;
            Ctx c = new Ctx(t, null, builtins, true, false);
            return renderNodes(f.defNodes, own == null ? t : own, c).trim();
        } finally { depth--; }
    }

    /** False while a toggle / condition this field depends on is in the wrong state. An unknown toggle never hides it. */
    public boolean visible(Field f, Map<String, String> own, Map<String, String> top, Map<String, String> builtins) {
        if (f.showIf != null) {
            String v = own == null ? null : own.get(f.showIf);
            if (v == null && top != null) v = top.get(f.showIf);
            if (v != null) {
                boolean on = "true".equals(v);
                if (f.showIfNot ? on : !on) return false;
            }
        }
        Guards g = f.guards;
        if (g.open || g.chains.isEmpty()) return true;
        Lookup l = lookup(own, top, builtins);
        for (List<Guard> chain : g.chains) {
            boolean all = true;
            for (Guard gd : chain) {
                if (gd.c.mentions(f.name)) continue;              // a field cannot hide itself
                if (gd.c.eval(l) == gd.neg) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ multi-select helpers

    public static List<String> selected(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String p : stored.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Stored form of a selection: the ticked options in the order they were declared. */
    public static String join(Field f, Collection<String> ticked) {
        List<String> out = new ArrayList<>();
        for (String o : f.options) if (ticked.contains(o)) out.add(o);
        return String.join("\n", out);
    }

    private static String multiText(Field f, String stored) {
        List<String> on = selected(stored);
        List<String> out = new ArrayList<>();
        for (String o : f.options) if (on.contains(o)) out.add(o);
        return String.join(f.sep, out);
    }

    // ------------------------------------------------------------------ rendering

    private static final class Ctx {
        final Map<String, String> top, builtins;
        final Map<String, List<Map<String, String>>> rows;
        final boolean plain, mask;
        Ctx(Map<String, String> top, Map<String, List<Map<String, String>>> rows, Map<String, String> builtins, boolean plain, boolean mask) {
            this.top = top; this.rows = rows; this.builtins = builtins; this.plain = plain; this.mask = mask;
        }
    }

    /**
     * @param values   field name -> value ("true"/"false" for toggles, "\n"-joined options for multi-select)
     * @param rows     group id -> one value map per row
     * @param builtins clipboard / selection / date / datetime -> text
     */
    public String render(Map<String, String> values, Map<String, List<Map<String, String>>> rows, Map<String, String> builtins) {
        return render(values, rows, builtins, false);
    }

    /** With maskSecrets the secret fields show as {@link #maskToken}; {@link #unmask} turns an (edited) preview back into the real command. */
    public String render(Map<String, String> values, Map<String, List<Map<String, String>>> rows, Map<String, String> builtins, boolean maskSecrets) {
        Map<String, String> top = values == null ? new LinkedHashMap<String, String>() : values;
        String out = renderNodes(nodes, top, new Ctx(top, rows, builtins, false, maskSecrets));
        int e = out.length();
        while (e > 0 && (out.charAt(e - 1) == ' ' || out.charAt(e - 1) == '\t')) e--;
        return out.substring(0, e);
    }

    public static String maskToken(int index) {
        String bullets = "••••••";
        return index <= 0 || index > 8 ? bullets : bullets + (char) ('₁' + index);
    }

    /** Replaces the mask tokens in text (typically the edited preview) with the real, quoted secret values. */
    public String unmask(String text, Map<String, String> values) {
        for (int i = secrets.size() - 1; i >= 0; i--) {
            Field f = secrets.get(i);
            String v = values == null ? null : values.get(f.name);
            v = v == null ? "" : v.trim();
            if (v.isEmpty()) continue;
            text = text.replace(maskToken(i), f.raw ? v : quote(v));
        }
        return text;
    }

    /** Required text fields that are still empty. */
    public List<String> missing(Map<String, String> values, Map<String, List<Map<String, String>>> rows) {
        return missing(values, rows, null);
    }

    public List<String> missing(Map<String, String> values, Map<String, List<Map<String, String>>> rows, Map<String, String> builtins) {
        List<String> miss = new ArrayList<>();
        for (Field f : topFields) if (f.required() && visible(f, values, values, builtins) && isEmpty(values, f)) miss.add(f.label());
        for (Group g : groups) {
            List<Map<String, String>> rs = rows == null ? null : rows.get(g.id);
            if (rs == null) continue;
            for (Map<String, String> r : rs)
                for (Field f : g.fields)
                    if (f.required() && visible(f, r, values, builtins) && isEmpty(r, f) && !miss.contains(f.label())) miss.add(f.label());
        }
        return miss;
    }

    private static boolean isEmpty(Map<String, String> v, Field f) {
        String s = v == null ? null : v.get(f.name);
        return s == null || s.trim().isEmpty();
    }

    private String renderNodes(List<Node> list, Map<String, String> values, Ctx c) {
        StringBuilder out = new StringBuilder();
        boolean eatLeading = false;
        for (Node n : list) {
            if (n instanceof Lit) {
                String t = ((Lit) n).text;
                if (eatLeading) {
                    int k = 0;
                    while (k < t.length() && (t.charAt(k) == ' ' || t.charAt(k) == '\t')) k++;
                    t = t.substring(k);
                    if (!t.isEmpty()) eatLeading = false;
                }
                out.append(t);
                continue;
            }
            String piece;
            if (n instanceof Fld) piece = renderField(((Fld) n).f, values, c);
            else if (n instanceof Ref) piece = renderRef((Ref) n, values, c);
            else if (n instanceof Builtin) {
                Builtin b = (Builtin) n;
                String v = c.builtins == null ? null : c.builtins.get(b.name);
                piece = (v == null || v.isEmpty()) ? "" : (b.raw || c.plain ? v : quote(v));
            } else if (n instanceof If) {
                If i = (If) n;
                boolean on = i.cond.eval(lookup(values, c.top, c.builtins));
                List<Node> branch = on ? i.then : i.els;
                piece = branch.isEmpty() ? "" : renderNodes(branch, values, c).trim();
            } else {
                Each e = (Each) n;
                List<Map<String, String>> rs = c.rows == null ? null : c.rows.get(e.id);
                StringBuilder sb = new StringBuilder();
                if (rs != null && !c.plain) for (Map<String, String> r : rs) {
                    String one = renderNodes(e.body, r, c).trim();
                    if (one.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(one);
                }
                piece = sb.toString();
            }
            if (piece.isEmpty()) {                                // omitted: swallow one neighbouring gap
                int e = out.length();
                while (e > 0 && (out.charAt(e - 1) == ' ' || out.charAt(e - 1) == '\t')) e--;
                out.setLength(e);
                if (e == 0 || out.charAt(e - 1) == '\n') eatLeading = true;
            } else {
                out.append(piece);
                eatLeading = false;
            }
        }
        return out.toString();
    }

    private String renderRef(Ref r, Map<String, String> values, Ctx c) {
        String v = resolve(r.name, values, c.top, c.builtins);
        if (v == null) return "";
        Field f = byName.get(r.name);
        if (f != null && f.kind == MULTI) v = multiText(f, v);
        v = v.trim();
        for (String fl : r.filters) v = applyFilter(v, fl);
        if (v.isEmpty()) return "";
        if (c.plain) return v;
        if (f != null && f.secret && c.mask) return maskToken(secrets.indexOf(f));
        return r.raw ? v : quote(v);
    }

    private String renderField(Field f, Map<String, String> values, Ctx c) {
        if (!visible(f, values, c.top, c.builtins)) return "";
        String v = values.get(f.name);
        switch (f.kind) {
            case TOGGLE:
                return "true".equals(v) ? f.flag : "";
            case CHOICE:
                if (v == null) v = f.options.isEmpty() ? "" : f.options.get(0);
                return v;
            case MULTI: {
                if (v == null) v = f.def;
                String j = multiText(f, v);
                if (j.isEmpty()) return "";
                return c.plain ? j : withPrefix(f, j);
            }
            default: {
                if (v == null) v = defaultFor(f, values, c.top, c.builtins);
                v = v.trim();
                if (v.isEmpty()) return "";
                if (c.plain) return v;
                int si = f.secret && c.mask ? secrets.indexOf(find(secrets, f.name)) : -1;
                String q = si >= 0 ? maskToken(si) : f.raw ? v : quote(v);
                return withPrefix(f, q);
            }
        }
    }

    private static String withPrefix(Field f, String q) {
        if (f.prefix.isEmpty()) return q;
        boolean glued = f.prefix.endsWith("=") || f.prefix.endsWith(":");
        return f.prefix + (glued ? "" : " ") + q;
    }

    /** Leaves plain words alone, single-quotes anything the shell would interpret. */
    public static String quote(String v) {
        if (SAFE.matcher(v).matches()) return v;
        return "'" + v.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ filters

    private static final Pattern URL = Pattern.compile(
            "^(?:([A-Za-z][A-Za-z0-9+.-]*)://)?(?:[^/@?#\\s]*@)?(\\[[^\\]]*\\]|[^/:?#\\s]*)(?::(\\d+))?([^?#]*)(?:[?#].*)?$", Pattern.DOTALL);

    static String applyFilter(String v, String filter) {
        switch (filter) {
            case "trim": return v.trim();
            case "upper": return v.toUpperCase(Locale.ROOT);
            case "lower": return v.toLowerCase(Locale.ROOT);
            case "slug": {
                String s = v.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
                return s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            }
            case "host": case "port": case "path": case "scheme": {
                Matcher m = URL.matcher(v.trim());
                if (!m.matches()) return "";
                String scheme = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
                switch (filter) {
                    case "scheme": return scheme;
                    case "host": {
                        String h = m.group(2);
                        return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
                    }
                    case "path": return m.group(4);
                    default: return m.group(3) != null ? m.group(3) : defaultPort(scheme);
                }
            }
            case "dir": {
                String s = stripSlashes(v.trim());
                int i = s.lastIndexOf('/');
                return i < 0 ? "." : i == 0 ? "/" : s.substring(0, i);
            }
            case "name": {
                String s = stripSlashes(v.trim());
                return s.substring(s.lastIndexOf('/') + 1);
            }
            case "stem": {
                String s = stripSlashes(v.trim());
                s = s.substring(s.lastIndexOf('/') + 1);
                int d = s.lastIndexOf('.');
                return d > 0 ? s.substring(0, d) : s;
            }
            case "ext": {
                String s = stripSlashes(v.trim());
                s = s.substring(s.lastIndexOf('/') + 1);
                int d = s.lastIndexOf('.');
                return d > 0 && d < s.length() - 1 ? s.substring(d + 1) : "";
            }
            case "urlencode": {
                StringBuilder sb = new StringBuilder();
                for (byte b : v.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    int x = b & 0xff;
                    if ((x >= 'A' && x <= 'Z') || (x >= 'a' && x <= 'z') || (x >= '0' && x <= '9') || x == '-' || x == '_' || x == '.' || x == '~') sb.append((char) x);
                    else sb.append('%').append(Character.toUpperCase(Character.forDigit(x >> 4, 16))).append(Character.toUpperCase(Character.forDigit(x & 15, 16)));
                }
                return sb.toString();
            }
            default: return v;
        }
    }

    private static String stripSlashes(String s) {
        int e = s.length();
        while (e > 1 && s.charAt(e - 1) == '/') e--;
        return s.substring(0, e);
    }

    private static String defaultPort(String scheme) {
        switch (scheme) {
            case "http": return "80";
            case "https": return "443";
            case "ssh": case "sftp": return "22";
            case "ftp": return "21";
            case "telnet": return "23";
            case "smb": return "445";
            case "rdp": return "3389";
            case "mysql": return "3306";
            case "postgres": case "postgresql": return "5432";
            default: return "";
        }
    }
}
