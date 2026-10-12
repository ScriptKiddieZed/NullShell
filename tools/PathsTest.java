import com.zed.nullshell.*;
import java.io.*;
import java.nio.file.*;
public class PathsTest {
  static int fails=0;
  static void eq(String n, Object got, Object want){ boolean ok=String.valueOf(got).equals(String.valueOf(want)); System.out.println((ok?"PASS ":"FAIL ")+n+(ok?"":"  got="+got+" want="+want)); if(!ok) fails++; }
  public static void main(String[] a) throws Exception {
    // package-private classes: reach them through reflection helpers in the same package via a tiny shim
    Path root=Files.createTempDirectory("rootfs");
    Files.createDirectories(root.resolve("usr/bin")); Files.createDirectories(root.resolve("root/proj/src")); Files.createDirectories(root.resolve("etc"));
    Files.write(root.resolve("root/proj/pyproject.toml"), "x".getBytes()); Files.write(root.resolve("root/proj/src/main.py"), "print(1)".getBytes());
    Files.write(root.resolve("etc/real.conf"), "r".getBytes());
    Files.createSymbolicLink(root.resolve("bin"), Paths.get("usr/bin"));                 // relative, like Debian's /bin
    Files.createSymbolicLink(root.resolve("root/abs"), Paths.get("/etc/real.conf"));     // absolute inside the guest
    Files.createSymbolicLink(root.resolve("root/escape"), Paths.get("/../../../../etc/passwd"));
    Files.createSymbolicLink(root.resolve("root/loop"), Paths.get("loop"));
    File r=root.toFile();
    eq("normalize", GuestPathsShim.normalize("/root/./a/../b//c/"), "/root/b/c");
    eq("normalize root", GuestPathsShim.normalize("/../.."), "/");
    eq("parent", GuestPathsShim.parent("/root/a.py"), "/root");
    eq("parent of top", GuestPathsShim.parent("/a"), "/");
    eq("name", GuestPathsShim.name("/root/a.py"), "a.py");
    eq("plain host path", GuestPathsShim.toHost(r,"/root/proj/src/main.py",true), new File(r,"root/proj/src/main.py"));
    eq("relative symlink dir", GuestPathsShim.toHost(r,"/bin/ls",true), new File(r,"usr/bin/ls"));
    eq("absolute symlink resolves inside rootfs", GuestPathsShim.toHost(r,"/root/abs",true), new File(r,"etc/real.conf"));
    eq("absolute symlink not followed when asked", GuestPathsShim.toHost(r,"/root/abs",false), new File(r,"root/abs"));
    eq("escape stays inside rootfs", GuestPathsShim.toHost(r,"/root/escape",true), new File(r,"etc/passwd"));
    eq("symlink loop gives null", GuestPathsShim.toHost(r,"/root/loop",true), null);
    eq("relative path rejected", GuestPathsShim.toHost(r,"root/x",true), null);
    eq("toGuest", GuestPathsShim.toGuest(r,new File(r,"root/proj/src/main.py")), "/root/proj/src/main.py");
    eq("toGuest outside", GuestPathsShim.toGuest(r,new File("/tmp")), null);
    eq("uri triple slash", GuestPathsShim.fromUri("file:///root/a%20b.py"), "/root/a b.py");
    eq("uri single slash", GuestPathsShim.fromUri("file:/root/a.py"), "/root/a.py");
    eq("uri other scheme", GuestPathsShim.fromUri("http://x/y"), null);
    eq("project root by marker", GuestPathsShim.projectRoot(r,"/root/proj/src/main.py"), "/root/proj");
    eq("no marker uses the folder", GuestPathsShim.projectRoot(r,"/etc/real.conf"), "/etc");
    eq("home is never a project", GuestPathsShim.projectRoot(r,"/root/notes.txt"), "/root");
    eq("lang py", GuestPathsShim.lang("script.PY"), "python");
    eq("lang ts uses js grammar", GuestPathsShim.scope("app.tsx"), "source.js");
    eq("lang dotfile", GuestPathsShim.lang(".bashrc"), "shell");
    eq("lang h is c", GuestPathsShim.lang("x.h"), "c");
    eq("lang unknown", GuestPathsShim.lang("notes.txt"), "null");
    eq("lang dockerfile", GuestPathsShim.lang("Dockerfile"), "null");
    eq("server override", GuestPathsShim.argv("pylsp","python -m pylsp -v"), "[python, -m, pylsp, -v]");
    eq("server default", GuestPathsShim.argv("tsserver",""), "[typescript-language-server, --stdio]");
    eq("every language server exists", GuestPathsShim.allServersResolve(), "true");
    System.out.println(fails==0?"ALL OK":"FAILURES: "+fails); System.exit(fails);
  }
}
