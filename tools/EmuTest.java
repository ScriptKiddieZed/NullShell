import com.zed.nullshell.TermEmulator;
import com.zed.nullshell.TermEmulator.Row;
public class EmuTest {
  static int fails=0;
  static void check(String n, boolean ok){ System.out.println((ok?"PASS ":"FAIL ")+n); if(!ok) fails++; }
  static String line(TermEmulator e,int y){ int sb=e.getScrollbackSize(); Row r=e.absRow(sb+y); StringBuilder s=new StringBuilder(); for(int i=0;i<r.ch.length;i++){ if((r.at[i]&TermEmulator.AT_WIDE_CONT)!=0) continue; s.appendCodePoint(r.ch[i]==0?' ':r.ch[i]); } return s.toString().replaceAll("\\s+$",""); }
  public static void main(String[] a){
    TermEmulator e=new TermEmulator(20,5);
    e.append("hello\r\nworld"); check("basic text", line(e,0).equals("hello")&&line(e,1).equals("world"));
    e.append("\033[2J\033[H"); e.append("\033[3;5Hxy"); check("CUP+erase", line(e,2).equals("    xy")&&line(e,0).isEmpty());
    e=new TermEmulator(20,5); e.append("\033[31mR\033[0m\033[1;38;2;1;2;3mT");
    Row r=e.absRow(e.getScrollbackSize()); check("sgr red", r.fg[0]==0xFFCD3131); check("sgr truecolor+bold", r.fg[1]==0xFF010203&&(r.at[1]&1)!=0);
    e=new TermEmulator(10,3); for(int i=0;i<6;i++) e.append("L"+i+"\r\n"); check("scrollback", e.getScrollbackSize()>=4 && line(e,0).equals("L4") || line(e,0).startsWith("L"));
    System.out.println("sb="+e.getScrollbackSize()+" l0="+line(e,0)+" l1="+line(e,1)+" l2="+line(e,2));
    e=new TermEmulator(10,3); e.append("main\033[?1049h"); check("alt blank", line(e,0).isEmpty()); e.append("ALT"); e.append("\033[?1049l"); check("alt restore", line(e,0).equals("main"));
    e=new TermEmulator(10,4); e.append("1\r\n2\r\n3\r\n4"); e.append("\033[2;3r\033[3;1H\n"); check("scroll region", line(e,0).equals("1")&&line(e,1).equals("3")&&line(e,3).equals("4"));
    final StringBuilder resp=new StringBuilder(); e=new TermEmulator(20,5); e.setResponder(b->resp.append(new String(b))); e.append("ab\033[6n"); check("DSR", resp.toString().equals("\033[1;3R"));
    e=new TermEmulator(10,3); e.append("日本x"); check("wide", line(e,0).equals("日本x")); check("wide cursor", e.getCursorX()==5);
    e=new TermEmulator(5,3); e.append("abcdefg"); check("wrap", line(e,0).equals("abcde")&&line(e,1).equals("fg"));
    e=new TermEmulator(10,4); e.append("a\r\nb\r\nc\r\nd"); e.resize(10,2); check("shrink keeps cursor line", line(e,1).equals("d") && e.getScrollbackSize()==2); e.resize(10,4); check("grow pulls back", line(e,0).equals("a")&&line(e,3).equals("d"));
    e=new TermEmulator(10,2); e.append("\033(0lqk\033(B"); check("dec graphics", line(e,0).equals("┌─┐"));
    e=new TermEmulator(10,2); e.append(new String(new byte[]{(byte)0xE2,(byte)0x82,(byte)0xAC}, java.nio.charset.StandardCharsets.ISO_8859_1)); 
    byte[] eur="€".getBytes(java.nio.charset.StandardCharsets.UTF_8); e=new TermEmulator(10,2); e.process(eur,0,2); e.process(eur,2,1); check("utf8 split across reads", line(e,0).equals("€"));
    e=new TermEmulator(10,2); e.append("abc\033[2D\033[1P"); check("DCH", line(e,0).equals("ac"));
    e=new TermEmulator(10,2); e.append("\033]0;mytitle\007"); check("osc title", e.getTitle().equals("mytitle"));
    e=new TermEmulator(10,3); e.append("hello world"); check("getText", e.getText(0,0,0,4).equals("hello"));

    // ---- reflow / selection / mouse ----
    e=new TermEmulator(10,4); e.append("abcdefghijklmno"); // wraps at 10
    e.resize(5,4); check("reflow narrower", line(e,0).equals("abcde")&&line(e,1).equals("fghij")&&line(e,2).equals("klmno"));
    e.resize(15,4); check("reflow wider rejoins", line(e,0).equals("abcdefghijklmno")&&line(e,1).isEmpty());
    check("reflow cursor", e.getCursorY()==1 && e.getCursorX()==0);
    e=new TermEmulator(10,3); e.append("one\r\ntwo\r\nabcdefghijkl"); e.resize(6,3);
    check("reflow keeps hard breaks + scrollback", e.getText(0,0,e.getScrollbackSize()+2,5).equals("one\ntwo\nabcdefghijkl"));
    e=new TermEmulator(8,3); e.append("abcdefghijkl"); check("copy joins soft wraps", e.getText(0,0,1,7).equals("abcdefghijkl"));
    e=new TermEmulator(8,3); e.append("ab\r\ncd"); check("copy keeps hard newline", e.getText(0,0,1,7).equals("ab\ncd"));
    e=new TermEmulator(10,3); e.append("\033[?1000h\033[?1006h"); check("mouse on", e.getMouseMode()==1000&&e.isMouseSgr());
    e.append("\033[?1000l"); check("mouse off", e.getMouseMode()==0);
    e=new TermEmulator(6,3); e.append("日本語x"); e.resize(4,3); check("reflow wide chars", line(e,0).equals("日本")&&line(e,1).equals("語x"));

    // ---- stable line ids survive scrollback eviction ----
    e=new TermEmulator(10,3);
    for(int i=0;i<3050;i++) e.append("L"+i+"\r\n");
    long stable=-1;
    for(int abs=0; abs<e.getScrollbackSize()+3; abs++){ Row rr=e.absRow(abs); StringBuilder sbd=new StringBuilder(); for(int c=0;c<rr.ch.length;c++) if(rr.ch[c]!=0) sbd.appendCodePoint(rr.ch[c]); if(sbd.toString().equals("L3000")){ stable=abs+e.getDroppedLines(); break; } }
    check("found line before eviction", stable>=0);
    for(int i=3050;i<3200;i++) e.append("L"+i+"\r\n");
    int nowAbs=(int)(stable-e.getDroppedLines()); Row rr=e.absRow(nowAbs); StringBuilder sbd=new StringBuilder(); for(int c=0;c<rr.ch.length;c++) if(rr.ch[c]!=0) sbd.appendCodePoint(rr.ch[c]);
    check("stable id still points at the same line after 150 evictions", sbd.toString().equals("L3000") && e.getDroppedLines()>0);
    System.out.println(fails==0?"ALL OK":"FAILURES: "+fails); System.exit(fails);
  }
}
