package com.zed.nullshell;
import java.util.*;
public class EditShim {
  public static String run(String text, int[][] r, String repl){
    List<TextEditApply.Edit> l=new ArrayList<>(); for(int[] x:r) l.add(new TextEditApply.Edit(x[0],x[1],x[2],x[3],repl));
    return TextEditApply.apply(text,l);
  }
}
