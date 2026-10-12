import com.zed.nullshell.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class ProcStatsTest {
  static int fails=0;
  static void check(String n, boolean ok){ System.out.println((ok?"PASS ":"FAIL ")+n); if(!ok) fails++; }
  public static void main(String[] a) throws Exception {
    final Object myUid = Files.getAttribute(Paths.get("/proc/self"),"unix:uid");
    ProcStats.Env env = new ProcStats.Env(){
      public long elapsedMs(){ try { return (long)(Double.parseDouble(new String(Files.readAllBytes(Paths.get("/proc/uptime"))).split(" ")[0])*1000); } catch(Exception e){ return 0; } }
      public long wallMs(){ return System.currentTimeMillis(); }
      public int cpus(){ return Runtime.getRuntime().availableProcessors(); }
      public boolean isMine(File d){ try { return myUid.equals(Files.getAttribute(d.toPath(),"unix:uid")); } catch(Exception e){ return false; } }
    };
    ProcStats ps = new ProcStats(new File("/proc"), env);
    ps.sample();                                   // baseline
    Thread.sleep(300);
    ps.sample();
    String s0 = ps.statText(), l0 = ps.loadavgText(), u0 = ps.uptimeText();
    long busy0 = Long.parseLong(s0.split("\n")[0].trim().split("\\s+")[1]);

    Process burner = new ProcessBuilder("sh","-c","while :; do :; done").start();   // one busy core, same uid
    int len = s0.length(), llen=l0.length(), ulen=u0.length(); boolean stable=true;
    for (int i=0;i<8;i++){ Thread.sleep(500); ps.sample(); if (ps.statText().length()!=len||ps.loadavgText().length()!=llen||ps.uptimeText().length()!=ulen) stable=false; }
    String s1 = ps.statText(), l1 = ps.loadavgText(), u1 = ps.uptimeText();
    burner.destroyForcibly();
    long busy1 = Long.parseLong(s1.split("\n")[0].trim().split("\\s+")[1]) + Long.parseLong(s1.split("\n")[0].trim().split("\\s+")[3]);
    long user1 = Long.parseLong(s1.split("\n")[0].trim().split("\\s+")[1]);
    System.out.println("user ticks before="+busy0+" after="+user1+"  (4s of one busy core ~ 400)");
    System.out.println(s1.split("\n")[0].trim());
    System.out.println(l1.trim()); System.out.println(u1.trim());
    check("burner counted as user CPU time", user1-busy0 > 200 && user1-busy0 < 700);
    check("file lengths constant across samples", stable);
    String[] lf = l1.trim().split("\\s+");
    check("loadavg parses and is > 0", lf.length==5 && Double.parseDouble(lf[0])>0.03 && lf[3].contains("/"));
    String[] uf = u1.trim().split("\\s+");
    check("uptime parses", uf.length==2 && Double.parseDouble(uf[0])>0);
    String[] lines = s1.split("\n");
    check("one line per cpu plus aggregate", lines[0].startsWith("cpu  ") && lines[ps.cpus()].startsWith("cpu"+(ps.cpus()-1)+" "));
    check("btime present", s1.contains("btime "));
    check("idle never negative", Long.parseLong(lines[0].trim().split("\\s+")[4])>=0);
    // monotonic counters
    long u2 = Long.parseLong(ps.statText().split("\n")[0].trim().split("\\s+")[1]);
    Thread.sleep(500); ps.sample();
    long u3 = Long.parseLong(ps.statText().split("\n")[0].trim().split("\\s+")[1]);
    check("user ticks never go backwards after the burner exits", u3>=u2);
    System.out.println(fails==0?"ALL OK":"FAILURES: "+fails); System.exit(fails);
  }
}
