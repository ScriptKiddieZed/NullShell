import com.zed.nullshell.*;
import java.util.*;
public class EditTest {
  static int fails=0;
  static void eq(String n, Object got, Object want){ boolean ok=String.valueOf(got).equals(String.valueOf(want)); System.out.println((ok?"PASS ":"FAIL ")+n+(ok?"":"\n   got : ["+got+"]\n   want: ["+want+"]")); if(!ok) fails++; }
  public static void main(String[] a){
    eq("rename twice on one line", EditShim.run("let foo = foo + 1;\nreturn foo;", new int[][]{{0,4,0,7},{0,10,0,13},{1,7,1,10}}, "bar"), "let bar = bar + 1;\nreturn bar;");
    eq("insert", EditShim.run("ab", new int[][]{{0,1,0,1}}, "XY"), "aXYb");
    eq("delete across lines", EditShim.run("one\ntwo\nthree", new int[][]{{0,1,2,2}}, ""), "oree");
    eq("crlf", EditShim.run("a\r\nbcd\r\ne", new int[][]{{1,1,1,2}}, "ZZ"), "a\r\nbZZd\r\ne");
    eq("column past end is clamped", EditShim.run("abc\ndef", new int[][]{{0,99,0,99}}, "!"), "abc!\ndef");
    eq("line past end appends", EditShim.run("abc", new int[][]{{9,0,9,0}}, "!"), "abc!");
    eq("emoji uses utf16 columns", EditShim.run("a\uD83D\uDE00b", new int[][]{{0,3,0,4}}, "X"), "a\uD83D\uDE00X");
    eq("overlapping edit is skipped (later start wins)", EditShim.run("abcdef", new int[][]{{0,1,0,4},{0,2,0,3}}, "_"), "ab_def");
    eq("reversed range tolerated", EditShim.run("abcdef", new int[][]{{0,4,0,1}}, "_"), "a_ef");
    eq("no edits", EditShim.run("same", new int[0][], "x"), "same");
    eq("whole document replace", EditShim.run("a\nb", new int[][]{{0,0,1,1}}, "new"), "new");
    System.out.println(fails==0?"ALL OK":"FAILURES: "+fails); System.exit(fails);
  }
}
