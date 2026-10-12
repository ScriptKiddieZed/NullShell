import com.zed.nullshell.*;
import java.util.*;
public class TemplateTest {
  static int fails=0;
  static void eq(String n, String got, String want){ boolean ok=got.equals(want); System.out.println((ok?"PASS ":"FAIL ")+n+(ok?"":"\n   got : ["+got+"]\n   want: ["+want+"]")); if(!ok) fails++; }
  static Map<String,String> v(String... kv){ Map<String,String> m=new LinkedHashMap<>(); for(int i=0;i<kv.length;i+=2) m.put(kv[i],kv[i+1]); return m; }
  static List<String> names(SnippetTemplate t){ List<String> o=new ArrayList<>(); for(SnippetTemplate.Field f:t.fields()) o.add(f.name); return o; }
  static Map<String,List<Map<String,String>>> mapRows(String id, Map<String,String> row){ Map<String,List<Map<String,String>>> m=new LinkedHashMap<>(); m.put(id, Arrays.asList(row)); return m; }
  static String r(String tpl, Map<String,String> vals){ return SnippetTemplate.parse(tpl).render(vals,null,null); }
  static String text(List<KeyTokens.Action> a){ StringBuilder s=new StringBuilder(); for(KeyTokens.Action x:a){ if(x.data==null) s.append("[sleep"+x.sleepMs+"]"); else s.append(new String(x.data).replace("\r","<CR>").replace("\033","<ESC>")); } return s.toString(); }
  public static void main(String[] a){
    eq("plain", r("ls -la", v()), "ls -la");
    eq("text field", r("ping {host}", v("host","10.0.0.1")), "ping 10.0.0.1");
    eq("quote spaces", r("cat {f}", v("f","my file.txt")), "cat 'my file.txt'");
    eq("quote injection", r("ping {h}", v("h","a; rm -rf /")), "ping 'a; rm -rf /'");
    eq("quote apostrophe", r("echo {s}", v("s","it's")), "echo 'it'\\''s'");
    eq("raw", r("tool {!opts?}", v("opts","-a 1 -b 2")), "tool -a 1 -b 2");
    eq("tilde path", r("ls {p}", v("p","~/Downloads")), "ls ~/Downloads");
    eq("toggle on/off", r("nmap {sv:-sV} {pn:-Pn} {t}", v("sv","true","pn","false","t","x.com")), "nmap -sV x.com");
    eq("optional empty middle", r("nmap {sv:-sV} {t}", v("sv","false","t","h")), "nmap h");
    eq("optional prefix", r("nmap {ports?-p} {t}", v("ports","1-1000","t","h")), "nmap -p 1-1000 h");
    eq("optional prefix empty", r("nmap {ports?-p} {t}", v("ports","","t","h")), "nmap h");
    eq("prefix glued =", r("tool {o?--out=}", v("o","a b")), "tool --out='a b'");
    eq("choice default first", r("nmap {timing=|-T3|-T4} {t}", v("t","h")), "nmap h");
    eq("choice picked", r("nmap {timing=|-T3|-T4} {t}", v("timing","-T4","t","h")), "nmap -T4 h");
    eq("text default", r("curl {url} -p {port=80}", v("url","x")), "curl x -p 80");
    eq("same var twice", r("cp {f} {f}.bak", v("f","a.txt")), "cp a.txt a.txt.bak");
    eq("flag target flag target", r("tool -a {o1?} {t1} -b {o2?} {t2}", v("o1","-x","t1","A","t2","B")), "tool -a -x A -b B");
    eq("shell var untouched", r("echo ${HOME} {x}", v("x","1")), "echo ${HOME} 1");
    eq("awk untouched", r("ps | awk '{print $1}' | head -{n}", v("n","3")), "ps | awk '{print $1}' | head -3");
    eq("find exec untouched", r("find . -exec ls {} \\; -name {p}", v("p","*.txt")), "find . -exec ls {} \\; -name '*.txt'");
    eq("escaped braces", r("echo {{literal}} {x}", v("x","1")), "echo {literal} 1");
    eq("leading empty", r("{a?} {b}", v("b","B")), "B");
    eq("multi-line", r("cd {d}\nls {f?}", v("d","/tmp")), "cd /tmp\nls");
    Map<String,String> bi=v("clipboard","http://a/b?x=1&y=2","date","2026-10-03");
    eq("builtin quoted", SnippetTemplate.parse("curl {clipboard} -o {date}.html").render(v(),null,bi), "curl 'http://a/b?x=1&y=2' -o 2026-10-03.html");
    eq("spaced toggle label", SnippetTemplate.parse("nmap {Service version:-sV} {t}").fields().get(0).label(), "Service version");
    eq("spaced toggle renders", r("nmap {Service version:-sV} {t}", v("Service version","true","t","h")), "nmap -sV h");
    eq("spaced optional prefix", r("nmap {Ports to scan?-p} {t}", v("Ports to scan","1-100","t","h")), "nmap -p 1-100 h");
    eq("spaced choice", r("nmap {Timing profile=|-T3|-T4} {t}", v("Timing profile","-T4","t","h")), "nmap -T4 h");
    eq("space before colon", r("x {Verbose :-v}", v("Verbose","true")), "x -v");
    eq("awk still literal", r("awk '{print $1}' f", v()), "awk '{print $1}' f");
    eq("brace expansion literal", r("echo {1..3} {a,b}", v()), "echo {1..3} {a,b}");
    eq("unicode label", SnippetTemplate.parse("x {Versi\u00F3n:-v}").fields().get(0).label(), "Versi\u00F3n");
    // repeating group
    SnippetTemplate t=SnippetTemplate.parse("tool {each: -a {mode=fast|deep} {target}} -v");
    Map<String,List<Map<String,String>>> rows=new LinkedHashMap<>(); String gid=t.groups().get(0).id;
    rows.put(gid, Arrays.asList(v("mode","fast","target","10.0.0.1"), v("mode","deep","target","10.0.0.2")));
    eq("each rows", t.render(v(),rows,null), "tool -a fast 10.0.0.1 -a deep 10.0.0.2 -v");
    rows.put(gid, new ArrayList<>()); eq("each empty", t.render(v(),rows,null), "tool -v");
    eq("fields list", String.valueOf(SnippetTemplate.parse("a {x} {y?} {z:-q} {x}").fields().size()), "3");
    eq("missing", String.join(",", SnippetTemplate.parse("a {x} {y?} {z=d}").missing(v(),null)), "X");
    eq("no fields", String.valueOf(SnippetTemplate.parse("awk '{print $1}'").hasFields()), "false");
    // ---- stage 1: filters, derived defaults, secrets, multi-select, conditions
    eq("filter host", r("ping {u|host}", v("u","https://user@Example.com:8443/a/b?x=1")), "ping Example.com");
    eq("filter port", r("nc {u|host} {u|port}", v("u","https://x.com/p")), "nc x.com 443");
    eq("filter port explicit", r("{u|port}", v("u","x.com:8080")), "8080");
    eq("filter path", r("{u|path}", v("u","https://x.com/a/b?x=1")), "/a/b");
    eq("filter scheme", r("{u|scheme}", v("u","ftp://h/f")), "ftp");
    eq("filter dir/name/stem/ext", r("{f|dir} {f|name} {f|stem} {f|ext}", v("f","/tmp/x/a.tar.gz")), "/tmp/x a.tar.gz a.tar gz");
    eq("filter dir root", r("{f|dir}", v("f","/a")), "/");
    eq("filter upper chain", r("{u|host|upper}", v("u","http://ab.com/x")), "AB.COM");
    eq("filter slug", r("scan_{t|slug}.txt", v("t","https://Ex-ample.com/a b")), "scan_ex_ample_com_a_b.txt");
    eq("filter urlencode", r("curl {!q|urlencode}", v("q","a b&c/\u00e9")), "curl a%20b%26c%2F%C3%A9");
    eq("ref quoted", r("echo {t|trim}", v("t","  a b  ")), "echo 'a b'");
    eq("ref declares field", String.join(",", names(SnippetTemplate.parse("x {t|host}"))), "t");
    eq("ref no builtin field", String.valueOf(SnippetTemplate.parse("x {clipboard|host}").fields().size()), "0");
    eq("ref builtin", SnippetTemplate.parse("ping {clipboard|host}").render(v(),null,v("clipboard","http://a.b/c")), "ping a.b");
    eq("unknown filter literal", r("echo {a|zzz}", v()), "echo {a|zzz}");
    eq("shell alt literal", r("echo {a|b}", v()), "echo {a|b}");
    eq("merge default", r("a {port} b {port=80}", v()), "a 80 b 80");
    eq("merge default 2", String.valueOf(SnippetTemplate.parse("a {port} b {port=80}").fields().size()), "1");
    eq("dynamic default", r("nmap {t} -oN {out=scan_{t|slug}.txt}", v("t","10.0.0.1")), "nmap 10.0.0.1 -oN scan_10_0_0_1.txt");
    eq("dynamic default overridden", r("nmap {t} -oN {out=scan_{t|slug}.txt}", v("t","h","out","mine.txt")), "nmap h -oN mine.txt");
    SnippetTemplate dd=SnippetTemplate.parse("nmap {t} -oN {out=scan_{t|slug}.txt}");
    eq("dynamic flag", String.valueOf(dd.fields().get(1).dynamic()), "true");
    eq("dynamic defaultFor", dd.defaultFor(dd.fields().get(1), v("t","a.b"), v("t","a.b"), null), "scan_a_b.txt");
    eq("dynamic not required", String.valueOf(dd.fields().get(1).required()), "false");
    eq("dynamic cycle safe", r("{a={a}}", v()), "");
    eq("shell var default", r("cd {d=${HOME}/x}", v()), "cd '${HOME}/x'");
    eq("secret masked", SnippetTemplate.parse("mysql -p{pw*}").render(v("pw","s3 cret"),null,null,true), "mysql -p"+SnippetTemplate.maskToken(0));
    eq("secret real", SnippetTemplate.parse("mysql -p{pw*}").render(v("pw","s3 cret"),null,null,false), "mysql -p's3 cret'");
    eq("secret prefix", SnippetTemplate.parse("tool {tok*?--token}").render(v("tok","abc"),null,null,true), "tool --token "+SnippetTemplate.maskToken(0));
    eq("secret list", String.valueOf(SnippetTemplate.parse("a {p*} {q*} {r}").secrets().size()), "2");
    SnippetTemplate sec=SnippetTemplate.parse("a {p*} {q*}");
    eq("unmask edited", sec.unmask("a "+SnippetTemplate.maskToken(0)+" -x "+SnippetTemplate.maskToken(1), v("p","one","q","t w")), "a one -x 't w'");
    eq("secret required", String.join(",", sec.missing(v("p","x"),null)), "Q");
    eq("star literal", r("echo {a*b}", v()), "echo {a*b}");
    eq("multi", r("nmap -p {ports+,:22|80|443} h", v("ports","443\n22")), "nmap -p 22,443 h");
    eq("multi none omitted", r("nmap {ports+,:22|80} h", v("ports","")), "nmap h");
    eq("multi prefix", r("nmap {s+,?--script=:vuln|safe} h", v("s","safe\nvuln")), "nmap --script=vuln,safe h");
    eq("multi prefix none", r("nmap {s+,?--script=:vuln|safe} h", v("s","")), "nmap h");
    eq("multi space sep", r("x {fl+ :-a|-b|-c}", v("fl","-c\n-a")), "x -a -c");
    eq("multi default ticked", r("p {ports+,:*22|80|*443}", v()), "p 22,443");
    eq("multi options", String.join("|", SnippetTemplate.parse("{p+,:a|b|c}").fields().get(0).options), "a|b|c");
    eq("multi join order", SnippetTemplate.join(SnippetTemplate.parse("{p+,:a|b|c}").fields().get(0), Arrays.asList("c","a")), "a\nc");
    eq("if true", r("nmap {deep:-A} {if deep: --script vuln} {t}", v("deep","true","t","h")), "nmap -A --script vuln h");
    eq("if false", r("nmap {deep:-A} {if deep: --script vuln} {t}", v("deep","false","t","h")), "nmap h");
    eq("if else", r("nmap {if deep: -A |else: -sV} {t}", v("deep","false","t","h")), "nmap -sV h");
    eq("if else true", r("nmap {if deep: -A |else: -sV} {t}", v("deep","true","t","h")), "nmap -A h");
    eq("if eq", r("x {m=fast|deep} {if m=deep: --long}", v("m","deep")), "x deep --long");
    eq("if eq no", r("x {m=fast|deep} {if m=deep: --long}", v("m","fast")), "x fast");
    eq("if ne", r("x {m=fast|deep} {if m!=deep: --quick}", v("m","fast")), "x fast --quick");
    eq("if not", r("x {d:-d} {if !d: --nod}", v("d","false")), "x --nod");
    eq("if and", r("x {a:} {b:} {if a && b: both}", v("a","true","b","true")), "x both");
    eq("if and no", r("x {a:} {b:} {if a && b: both}", v("a","true","b","false")), "x");
    eq("if or", r("x {a:} {b:} {if a || b: any}", v("a","false","b","true")), "x any");
    eq("if quoted value", r("x {m=a b|c} {if m='a b': yes}", v("m","a b")), "x a b yes".replace("a b yes","'a b' yes".substring(0,0)+"a b yes"));
    eq("if multi member", r("{p+,:a|b} {if p=b: B}", v("p","a\nb")), "a,b B");
    eq("if text truthy", r("{o?} {if o: has}", v("o","1")), "1 has");
    eq("if field in branch", r("{m=a|b} {if m=b: -x {lvl=3}}", v("m","b")), "b -x 3");
    eq("if guard hides field", String.valueOf(SnippetTemplate.parse("{m=a|b} {if m=b: -x {lvl}}").visible(SnippetTemplate.parse("{m=a|b} {if m=b: -x {lvl}}").fields().get(1), v("m","a"), v("m","a"), null)), "false");
    SnippetTemplate g2=SnippetTemplate.parse("{m=a|b} {if m=b: -x {lvl}}");
    eq("guard shows", String.valueOf(g2.visible(g2.fields().get(1), v("m","b"), v("m","b"), null)), "true");
    eq("guard missing skips hidden", String.join(",", g2.missing(v("m","a"),null)), "");
    eq("guard missing when shown", String.join(",", g2.missing(v("m","b"),null)), "Lvl");
    eq("guard else field", String.valueOf(SnippetTemplate.parse("{d:} {if d: x |else: {alt}}").missing(v("d","true"),null).size()), "0");
    eq("self guard ignored", String.valueOf(SnippetTemplate.parse("{if s: --script={s+,:a|b}}").hasFields()), "true");
    SnippetTemplate sg=SnippetTemplate.parse("{if s: --script={s+,:a|b}}");
    eq("self guard visible", String.valueOf(sg.visible(sg.fields().get(0), v(), v(), null)), "true");
    eq("self guard render", sg.render(v("s","b"),null,null), "--script=b");
    eq("if nested", r("{a:} {b:} {if a: A {if b: B}}", v("a","true","b","true")), "A B");
    eq("if inside each", SnippetTemplate.parse("t {each: {m=a|b} {if m=b: --b}}").render(v(),mapRows("g0", v("m","b")),null), "t b --b");
    eq("problem unknown", String.join(";", SnippetTemplate.parse("{if zz: x}").problems()).contains("zz")?"y":"n", "y");
    eq("problem none", String.valueOf(SnippetTemplate.parse("{d:} {if d: x}").problems().size()), "0");
    eq("empty toggle controls only", r("{d:} go {if d: more}", v("d","true")), "go more");
    // key tokens
    eq("tokens plain+enter", text(KeyTokens.parse("ls -la",true)), "ls -la<CR>");
    eq("tokens no enter", text(KeyTokens.parse("ls -la",false)), "ls -la");
    eq("tokens multiline", text(KeyTokens.parse("a\nb",true)), "a<CR>b<CR>");
    eq("tokens keys", text(KeyTokens.parse("sleep 100<C-c><sleep 250>ls<Enter>",false)), "sleep 100\u0003[sleep250]ls<CR>");
    eq("tokens redirect untouched", text(KeyTokens.parse("cat < in.txt > out 2>&1",false)), "cat < in.txt > out 2>&1");
    eq("tokens arrows", text(KeyTokens.parse("<Up><Esc>",false)), "\033[A<ESC>".replace("\033","<ESC>"));
    eq("tokens lt", text(KeyTokens.parse("a<lt>b",false)), "a<b");
    eq("strip", KeyTokens.stripTokens("x<Enter>y<sleep 5><lt>"), "xy<");
    System.out.println(fails==0?"ALL OK":"FAILURES: "+fails); System.exit(fails);
  }
}
