package com.afs.guard;

public final class MemoTextFormatTest {
    private static int checks;
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private static void eq(String expected,String actual,String label){check(expected.equals(actual),label+" expected="+expected+" actual="+actual);}
    public static void main(String[] args){
        eq("# abc",MemoTextFormat.heading("abc",0,0,1).body,"heading");
        eq("### abc",MemoTextFormat.heading("## abc",2,2,3).body,"replace heading");
        eq("abc",MemoTextFormat.heading("# abc",0,0,0).body,"normal paragraph");
        eq("one",MemoTextFormat.heading("1. one",0,6,0).body,"normal paragraph removes ordered marker");
        eq("one",MemoTextFormat.heading("- one",0,5,0).body,"normal paragraph removes bullet");
        eq("one\n- [x] done\ntwo",MemoTextFormat.heading("1. one\n- [x] done\n2. two",0,99,0).body,"normal paragraph keeps task state");
        eq("# one",MemoTextFormat.heading("> 1. one",0,8,1).body,"heading removes stacked paragraph markers");
        eq("## one\ntwo",MemoTextFormat.heading("one\ntwo",0,4,2).body,"terminal newline excludes next paragraph");
        eq("  ## one\r\n## two",MemoTextFormat.heading("  one\r\ntwo",0,99,2).body,"CRLF and indentation retained");
        eq("- [x] done",MemoTextFormat.heading("- [x] done",0,99,1).body,"heading does not destroy checked task");
        eq("**abc**",MemoTextFormat.inline("abc",0,3,"bold").body,"bold selection");
        eq("abc",MemoTextFormat.inline("**abc**",2,5,"bold").body,"unwrap surrounding pair");
        eq("abc",MemoTextFormat.inline("**abc**",0,7,"bold").body,"unwrap selected pair");
        eq("*abc*",MemoTextFormat.inline("abc",3,0,"italic").body,"reverse selection");
        eq("<u>abc</u>",MemoTextFormat.inline("abc",0,3,"underline").body,"underline convention");
        eq("==abc==",MemoTextFormat.inline("abc",0,3,"highlight").body,"highlight convention");
        eq("~~abc~~",MemoTextFormat.inline("abc",0,3,"strike").body,"strike convention");
        check(MemoTextFormat.inline("abc",1,1,"bold").cursor==3,"caret inside empty pair");
        eq("- [x] abc",MemoTextFormat.inline("- [x] abc",0,4,"bold").body,"task prefix guarded");
        eq("- [x] **abc**",MemoTextFormat.inline("- [x] abc",6,9,"bold").body,"task body formatting keeps completion");
        eq("1. one\n2. two",MemoTextFormat.list("one\ntwo",0,99,true).body,"ordered list");
        eq("- one\n- two",MemoTextFormat.list("1. one\n2. two",0,99,false).body,"ordered to unordered");
        eq("1. one\n- [x] done\n2. two",MemoTextFormat.list("one\n- [x] done\ntwo",0,99,true).body,"list preserves task state and numbering");
        eq("- one",MemoTextFormat.list("## one",0,6,false).body,"heading to list");
        eq("  - [x] done",MemoTextFormat.indent("- [x] done",0,99,true).body,"task indentation");
        eq("- [x] done",MemoTextFormat.indent("  - [x] done",0,99,false).body,"task outdent");
        eq("abc",MemoTextFormat.indent("abc",0,0,false).body,"top level outdent no-op");
        String max="            abc";eq(max,MemoTextFormat.indent(max,0,99,true).body,"indent limit");
        String code="```md\nabc\n```\ntext";
        eq("```md\nabc\n```\n# text",MemoTextFormat.heading(code,0,99,1).body,"skip fenced paragraphs");
        eq(code,MemoTextFormat.inline(code,6,9,"bold").body,"skip fenced inline text");
        String tilde="~~~~\nabc\n~~~\ntext\n~~~~\nend";
        eq("~~~~\nabc\n~~~\ntext\n~~~~\n# end",MemoTextFormat.heading(tilde,0,99,1).body,"closing fence length");
        StringBuilder full=new StringBuilder();for(int i=0;i<20000;i++)full.append('a');
        eq(full.toString(),MemoTextFormat.heading(full.toString(),0,0,1).body,"capacity protection");
        eq("<u>中文</u>",MemoTextFormat.previewExtras("&lt;u&gt;中文&lt;/u&gt;"),"safe underline preview");
        check(MemoTextFormat.previewExtras("&lt;script&gt;x&lt;/script&gt;").contains("&lt;script&gt;"),"arbitrary HTML remains escaped");
        String blocks=MemoTextFormat.previewExtras("#### H4\n##### H5\n###### H6\n> quote one\n> quote two\nplain\n---\n***\n1. one\n2. two\n```\n#### not a heading\n> not a quote\n1. not a list\n```");
        check(blocks.contains("<big><b><font color='#000000'>H4</font>"),"h4 preview is promoted");
        check(blocks.contains("<big><b><font color='#000000'>H5</font>"),"h5 preview is promoted");
        check(blocks.contains("<big><b><font color='#000000'>H6</font>"),"h6 preview is promoted");
        check(blocks.contains("<i><font color='#8A8F98'>quote one</font></i>"),"first quote line is styled");
        check(blocks.contains("<i><font color='#8A8F98'>quote two</font></i>"),"second quote line is styled");
        check(blocks.contains("─────"),"horizontal rule preview");
        check(blocks.contains("1. one")&&blocks.contains("2. two"),"ordered list numbers are retained");
        check(blocks.contains("#### not a heading")&&blocks.contains("> not a quote")&&blocks.contains("1. not a list"),"fenced code remains literal");
        check(MemoTextFormat.previewExtras("&gt; &lt;script&gt;x&lt;/script&gt;").contains("&lt;script&gt;"),"quote preview keeps HTML escaped");
        check(MemoTextFormat.previewExtras("==中文==").contains("background-color"),"highlight preview");
        check(MemoTextFormat.previewExtras("&lt;u onclick=x&gt;x&lt;/u&gt;").contains("onclick=x&gt;"),"underline attributes never enabled");
        eq("```\n==example==\n```",MemoTextFormat.previewExtras("```\n==example==\n```"),"preview leaves fenced examples untouched");
        eq("==abc==\n```\ncode\n```",MemoTextFormat.inline("abc\n```\ncode\n```",0,3,"highlight").body,"selection before code remains formatable");
        boolean invalid=false;try{MemoTextFormat.heading("a",0,0,7);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"invalid heading rejected");
        invalid=false;try{MemoTextFormat.inline("a",0,1,"invalid");}catch(IllegalArgumentException e){invalid=true;}check(invalid,"unknown format rejected");
        // 有序列表回车续号（下标都是插入的那个 \n 的位置）
        eq("1. one\n2. ",MemoTextFormat.continueList("1. one\n",6).body,"enter continues numbering");
        eq("10",String.valueOf(MemoTextFormat.continueList("1. one\n",6).cursor),"cursor lands after the new marker");
        eq("  9) x\n  10) ",MemoTextFormat.continueList("  9) x\n",6).body,"indent and delimiter kept");
        eq("13",String.valueOf(MemoTextFormat.continueList("  9) x\n",6).cursor),"cursor after indented marker");
        eq("12. a\n13. ",MemoTextFormat.continueList("12. a\n",5).body,"two digit number");
        eq("1. one\n\n",MemoTextFormat.continueList("1. one\n2. \n",10).body,"empty item ends the list");
        eq("7",String.valueOf(MemoTextFormat.continueList("1. one\n2. \n",10).cursor),"cursor stays on the empty line");
        check(MemoTextFormat.continueList("1. one\n",0)==null,"nothing to continue at position 0");
        check(MemoTextFormat.continueList("plain text\n",10)==null,"plain line has no numbering");
        check(MemoTextFormat.continueList("- bullet\n",8)==null,"bullet list is not auto-numbered");
        check(MemoTextFormat.continueList("- [ ] task\n",10)==null,"task line is not auto-numbered");
        check(MemoTextFormat.continueList("```\n1. code\n",11)==null,"fenced code never continued");
        eq("\none",MemoTextFormat.continueList("1.\none",2).body,"bare marker counts as empty item");
        String numbered="1. one\n2. two\n3. three";
        int middle=numbered.indexOf("2. two");
        String middleDeleted=numbered.substring(0,middle)+numbered.substring(middle+"2. two\n".length());
        MemoTextFormat.Change repaired=MemoTextFormat.renumberAfterDeletion(numbered,middleDeleted,middle,"2. two\n".length(),middle);
        eq("1. one\n2. three",repaired.body,"deleting a middle item renumbers the remainder");
        eq("7",String.valueOf(repaired.cursor),"renumber keeps the caret at the deletion point");
        String firstDeleted=numbered.substring(numbered.indexOf('\n')+1);
        eq("1. two\n2. three",MemoTextFormat.renumberAfterDeletion(numbered,firstDeleted,0,"1. one\n".length(),0).body,"deleting the first item restarts numbering");
        String nested="1. parent\n  1. child\n2. middle\n3. last\n```\n9. code\n```";
        int nestedDelete=nested.indexOf("2. middle");
        String nestedAfter=nested.substring(0,nestedDelete)+nested.substring(nestedDelete+"2. middle\n".length());
        eq("1. parent\n  1. child\n2. last\n```\n9. code\n```",
                MemoTextFormat.renumberAfterDeletion(nested,nestedAfter,nestedDelete,"2. middle\n".length(),nestedDelete).body,
                "nested items keep a numbering stack and fenced code stays literal");
        String rollover="9. nine\n10. ten\n11. eleven";
        String rolloverAfter=rollover.substring("9. nine\n".length());
        eq("1. ten\n2. eleven",MemoTextFormat.renumberAfterDeletion(rollover,rolloverAfter,0,"9. nine\n".length(),0).body,"two digit markers normalize to one digit");
        check(MemoTextFormat.renumberAfterDeletion("plain text","lain text",1,1,0)==null,"plain text deletion does not rewrite numbering");
        eq("> note",MemoTextFormat.quote("note",0,4).body,"quote selected paragraph");
        eq("note",MemoTextFormat.quote("> note",0,6).body,"quote toggles off");
        eq("note",MemoTextFormat.heading("> note",0,6,0).body,"body removes quote");
        eq("```\ncode\n```",MemoTextFormat.quote("```\ncode\n```",0,12).body,"quote skips fenced code");
        eq("- [x] done",MemoTextFormat.quote("- [x] done",0,10).body,"quote preserves task completion");
        System.out.println("Memo text format: "+checks+" checks passed");
    }
}
