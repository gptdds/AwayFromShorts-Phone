package com.afs.guard;
import java.util.*;
public final class ToolShelfModelTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        ToolShelfModel m=new ToolShelfModel(null);
        check(m.home().size()==6&&m.box().size()==3);
        check(m.toHome("pomodoro","memo"));check(m.home().indexOf("pomodoro")<m.home().indexOf("memo"));
        check(!m.box().contains("pomodoro"));check(m.toBox("memo"));
        check(m.box().contains("memo")&&!m.home().contains("memo"));
        check(!m.toBox("toolbox")&&!m.toHome("unknown",null));
        for(int i=0;i<20;i++){m.toHome("memo",null);m.toBox("memo");}
        ToolShelfModel restored=new ToolShelfModel(m.home());check(restored.home().equals(m.home()));
        check(new ToolShelfModel(Arrays.asList("memo","memo","unknown")).home().equals(Arrays.asList("memo","toolbox")));
        check(new ToolShelfModel(Collections.emptyList()).box().size()==8);
        Set<String> all=new HashSet<>(m.home());all.addAll(m.box());check(all.size()==ToolShelfModel.ALL.size());
        check(m.home().size()+m.box().size()==all.size());
        check(m.toBox("future","mindful"));check(m.box().indexOf("future")<m.box().indexOf("mindful"));
        check(new ToolShelfModel(m.home(),m.box()).box().equals(m.box()));
        check(!m.toBox("future","future"));
        System.out.println("Tool shelf moves, persistence and membership passed");
    }
}
