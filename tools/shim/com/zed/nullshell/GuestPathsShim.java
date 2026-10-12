package com.zed.nullshell;
import java.io.File;
public class GuestPathsShim {
  public static String normalize(String p){ return GuestPaths.normalize(p); }
  public static String parent(String p){ return GuestPaths.parent(p); }
  public static String name(String p){ return GuestPaths.name(p); }
  public static File toHost(File r,String g,boolean f){ return GuestPaths.toHost(r,g,f); }
  public static String toGuest(File r,File h){ return GuestPaths.toGuest(r,h); }
  public static String fromUri(String u){ return GuestPaths.fromUri(u); }
  public static String projectRoot(File r,String g){ return GuestPaths.projectRoot(r,g); }
  public static String lang(String f){ LanguageSupport.Lang l=LanguageSupport.forFile(f); return l==null?"null":l.id; }
  public static String scope(String f){ LanguageSupport.Lang l=LanguageSupport.forFile(f); return l==null?"null":l.scope; }
  public static String argv(String id,String o){ return LanguageSupport.argv(LanguageSupport.server(id),o).toString(); }
  public static String allServersResolve(){ for(LanguageSupport.Lang l:LanguageSupport.languages()) if(l.serverId!=null && LanguageSupport.server(l.serverId)==null) return "false"; return "true"; }
}
