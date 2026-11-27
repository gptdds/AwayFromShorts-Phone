package com.afs.guard;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Markdown formatting; display spans never replace source text or composing spans. */
public final class MemoTextFormat {
    private MemoTextFormat(){}
    private static final int MAX_BODY=20000;
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern TASK=Pattern.compile("^[ \\t]*[-*+] \\[[ xX]\\] ");
    private static final Pattern HEADING=Pattern.compile("^(#{1,6}) ");
    private static final Pattern LIST=Pattern.compile("^(?:[-*+] |[0-9]{1,9}[.)] )");
    /** 有序列表项：缩进 + 序号 + 分隔符(. 或 )) + 内容（内容可能还是空的）。 */
    private static final Pattern ORDERED=Pattern.compile("^([ \\t]*)([0-9]{1,9})([.)])(?:[ \\t]+(.*))?$");
    private static final Pattern HR=Pattern.compile("^(?:-{3,}|\\*{3,}|_{3,})$");
    public static final class Change {
        public final String body;
        public final int cursor;
        public Change(String body,int cursor){this.body=body;this.cursor=clamp(cursor,body.length());}
    }
    private static final class Line {
        int start,end;boolean code;
        Line(int start,int end,boolean code){this.start=start;this.end=end;this.code=code;}
    }
    private static int clamp(int value,int size){return Math.max(0,Math.min(value,size));}
    private static List<Line> lines(String body){
        List<Line> out=new ArrayList<>();char fence=0;int length=0;
        for(int start=0;start<=body.length();){
            int next=body.indexOf('\n',start),end=next<0?body.length():next;if(end>start&&body.charAt(end-1)=='\r')end--;
            Matcher m=FENCE.matcher(body.substring(start,end));boolean code=fence!=0;
            if(m.matches()){
                code=true;String run=m.group(1);
                if(fence==0){fence=run.charAt(0);length=run.length();}
                else if(run.charAt(0)==fence&&run.length()>=length&&m.group(2).trim().isEmpty())fence=0;
            }
            out.add(new Line(start,end,code));if(next<0)break;start=next+1;
        }return out;
    }
    private static List<Line> selected(String body,int anchor,int focus){
        int low=clamp(Math.min(anchor,focus),body.length()),high=clamp(Math.max(anchor,focus),body.length());
        int start=body.lastIndexOf('\n',low-1)+1;if(high>low&&body.charAt(high-1)=='\n')high--;
        List<Line> out=new ArrayList<>();for(Line line:lines(body))if(line.start>=start&&line.start<=high)out.add(line);return out;
    }
    private static boolean codeAt(String body,int start,int end){
        for(Line line:lines(body))if(line.code&&(start==end?start>=line.start&&start<=line.end:start<line.end&&end>line.start))return true;return false;
    }
    private static int indentEnd(String line){int i=0;while(i<line.length()&&(line.charAt(i)==' '||line.charAt(i)=='\t'))i++;return i;}
    /** Remove paragraph prefixes in any stacked order, leaving visible content intact. */
    private static int paragraphMarkerEnd(String text,int indentation){
        int end=indentation;
        while(end<text.length()){
            String remainder=text.substring(end);
            Matcher heading=HEADING.matcher(remainder);
            if(heading.find()){end+=heading.end();continue;}
            if(remainder.startsWith("> ")){end+=2;continue;}
            Matcher list=LIST.matcher(remainder);
            if(list.find()){end+=list.end();continue;}
            break;
        }
        return end;
    }
    private static Change changed(String original,String body,int cursor,int oldFocus){return body.length()>MAX_BODY?new Change(original,oldFocus):new Change(body,cursor);}
    /** level=0 restores normal paragraph; completed tasks are never converted to headings. */
    public static Change heading(String body,int anchor,int focus,int level){
        if(level<0||level>3)throw new IllegalArgumentException("Heading level must be 0..3");
        return paragraphs(body,anchor,focus,0,level);
    }
    /** Existing task lines stay task lines, including their checked state. */
    public static Change quote(String body,int anchor,int focus){return paragraphs(body,anchor,focus,3,0);}
    public static Change list(String body,int anchor,int focus,boolean ordered){return paragraphs(body,anchor,focus,1,ordered?1:0);}
    /** Each level adds two spaces; at most twelve indentation characters. */
    public static Change indent(String body,int anchor,int focus,boolean more){return paragraphs(body,anchor,focus,2,more?1:0);}

    /**
     * 站在有序列表项末尾敲回车时，自动接上下一行序号。
     *
     * <pre>
     *   1. 背单词      回车        1. 背单词
     *                           →   2. |
     * </pre>
     *
     * <p>三条边界都有意为之：
     * <ul>
     *   <li>空条目再回车 → 把标记删掉、只留空行（不然列表永远退不出来）；</li>
     *   <li>缩进和 {@code .}/{@code )} 原样沿用，1) 不会突然变成 1.；</li>
     *   <li>代码块（``` 围起来的部分）里什么都不接，那里的数字是代码。</li>
     * </ul>
     *
     * @param newlineAt 刚插入的那个 '\n' 的下标
     * @return 需要改文本就返回新的 body/cursor；不用动（不是有序列表项）返回 null
     */
    public static Change continueList(String body,int newlineAt){
        int at=clamp(newlineAt,body.length());
        if(at<=0||at>=body.length()||body.charAt(at)!='\n')return null;
        int start=body.lastIndexOf('\n',at-1)+1;
        for(Line line:lines(body))if(line.code&&line.start==start)return null;
        Matcher m=ORDERED.matcher(body.substring(start,at));
        if(!m.matches())return null;
        String indent=m.group(1),digits=m.group(2),mark=m.group(3),text=m.group(4);
        if(text==null||text.trim().isEmpty()){
            // 空条目回车：收尾，把标记整段删掉，光标留在空行行首
            return new Change(body.substring(0,start)+body.substring(at),start);
        }
        int next;
        try{next=Integer.parseInt(digits)+1;}catch(NumberFormatException error){return null;}
        if(next<1||next>999999999)return null;
        String prefix=indent+next+mark+" ";
        int insert=at+1;
        return new Change(body.substring(0,insert)+prefix+body.substring(insert),insert+prefix.length());
    }
    /**
     * Repair numbering after an edit removes a list item or part of an ordered line.
     * Fenced code, bullets and task lines are left untouched. Indentation and the
     * original "." or ")" delimiter are retained.
     */
    public static Change renumberAfterDeletion(String before,String body,int editStart,int removedCount,int cursor){
        if(removedCount<=0||before==null||body==null)return null;
        int editEnd=Math.min(before.length(),Math.max(editStart,0)+removedCount);
        boolean affected=false;
        for(Line line:lines(before)){
            if(line.code)continue;
            String text=before.substring(line.start,line.end);
            if(ORDERED.matcher(text).matches()
                    &&editStart<=line.end&&editEnd>=line.start)affected=true;
        }
        return affected?renumberOrdered(body,cursor):null;
    }
    private static final class NumberFrame {
        final int indent;char delimiter;int next;
        NumberFrame(int indent,char delimiter){this.indent=indent;this.delimiter=delimiter;next=1;}
    }
    private static final class PrefixEdit {
        final int start,end;final String replacement;
        PrefixEdit(int start,int end,String replacement){this.start=start;this.end=end;this.replacement=replacement;}
    }
    private static int indentColumn(String text){
        int column=0;
        for(int i=0;i<text.length();i++){
            char ch=text.charAt(i);
            if(ch==' ')column++;
            else if(ch=='\t')column+=4-(column%4);
            else break;
        }
        return column;
    }
    private static Change renumberOrdered(String body,int cursor){
        List<NumberFrame> frames=new ArrayList<>();
        List<PrefixEdit> edits=new ArrayList<>();
        for(Line line:lines(body)){
            String text=body.substring(line.start,line.end);
            if(line.code){
                frames.clear();
                continue;
            }
            int indentation=indentEnd(text);
            Matcher ordered=ORDERED.matcher(text.substring(indentation));
            if(ordered.matches()){
                int column=indentColumn(text);
                while(!frames.isEmpty()&&frames.get(frames.size()-1).indent>column)frames.remove(frames.size()-1);
                NumberFrame frame=frames.isEmpty()?null:frames.get(frames.size()-1);
                char delimiter=ordered.group(3).charAt(0);
                if(frame==null||frame.indent<column){
                    frame=new NumberFrame(column,delimiter);
                    frames.add(frame);
                }else if(frame.indent==column){
                    if(frame.delimiter==delimiter)frame.next++;
                    else{frame.delimiter=delimiter;frame.next=1;}
                }
                String value=ordered.group(0);
                int digits=ordered.group(2).length();
                int contentAt=ordered.start(4);
                int prefixEnd=indentation+(contentAt<0?value.length():contentAt);
                String spacing=text.substring(indentation+digits+1,prefixEnd);
                String prefix=text.substring(0,indentation)+frame.next+delimiter+spacing;
                if(!prefix.equals(text.substring(0,prefixEnd)))
                    edits.add(new PrefixEdit(line.start,line.start+prefixEnd,prefix));
                continue;
            }
            if(text.trim().isEmpty()||indentation==0)frames.clear();
        }
        if(edits.isEmpty())return null;
        StringBuilder out=new StringBuilder(body);
        int moved=cursor;
        for(int i=edits.size()-1;i>=0;i--){
            PrefixEdit edit=edits.get(i);
            out.replace(edit.start,edit.end,edit.replacement);
            int delta=edit.replacement.length()-(edit.end-edit.start);
            if(moved>=edit.end)moved+=delta;
            else if(moved>edit.start)moved=edit.start+edit.replacement.length();
        }
        return new Change(out.toString(),moved);
    }
    private static Change paragraphs(String body,int anchor,int focus,int operation,int argument){
        List<Line> chosen=selected(body,anchor,focus);StringBuilder out=new StringBuilder(body);int cursor=clamp(focus,body.length());
        int number=0;java.util.HashMap<Integer,Integer> numbers=new java.util.HashMap<>();
        for(Line line:chosen){String text=body.substring(line.start,line.end);if(!line.code&&!TASK.matcher(text).find())numbers.put(line.start,++number);}
        for(int n=chosen.size()-1;n>=0;n--){
            Line line=chosen.get(n);if(line.code)continue;String text=body.substring(line.start,line.end);int indentation=indentEnd(text),start=line.start,end=start;String replacement;
            if(operation==2){
                if(argument==1){if(text.substring(0,indentation).replace("\t","  ").length()>=12)continue;replacement="  ";}
                else{if(indentation==0)continue;end=start+(text.startsWith("  ")?2:1);replacement="";}
            }else{
                if(TASK.matcher(text).find())continue;start+=indentation;end=start;String remainder=text.substring(indentation);
                if(operation==0){end=line.start+paragraphMarkerEnd(text,indentation);replacement=argument==0?"":(argument==1?"# ":argument==2?"## ":"### ");}
                else if(operation==3){replacement=remainder.startsWith("> ")?"":"> ";if(remainder.startsWith("> "))end=start+2;}
                else{
                    Matcher h=HEADING.matcher(remainder);if(h.find())end+=h.end();
                    Matcher list=LIST.matcher(remainder.substring(end-start));if(list.find())end+=list.end();
                    replacement=argument==1?numbers.get(line.start)+". ":"- ";
                }
            }
            out.replace(start,end,replacement);int delta=replacement.length()-(end-start);
            if(cursor>=end)cursor+=delta;else if(cursor>start)cursor=start+replacement.length();
        }return changed(body,out.toString(),cursor,focus);
    }
    /** Styles: bold, italic, strike, underline, highlight. Select text, or insert an empty pair at the caret. */
    public static Change inline(String body,int anchor,int focus,String style){
        int start=clamp(Math.min(anchor,focus),body.length()),end=clamp(Math.max(anchor,focus),body.length());
        String left,right;
        if("bold".equals(style)){left=right="**";}else if("italic".equals(style)){left=right="*";}
        else if("strike".equals(style)){left=right="~~";}else if("underline".equals(style)){left="<u>";right="</u>";}
        else if("highlight".equals(style)){left=right="==";}else throw new IllegalArgumentException("Unknown inline style");
        if(codeAt(body,start,end))return new Change(body,focus);
        // Formatting inside the marker would corrupt completion state.
        for(MemoChecklist.Item task:MemoChecklist.items(body))if(start<task.content&&end>task.marker||start==end&&start>=task.marker&&start<task.content)return new Change(body,focus);
        String selected=body.substring(start,end);
        if(start>=left.length()&&end+right.length()<=body.length()&&body.substring(start-left.length(),start).equals(left)&&body.substring(end,end+right.length()).equals(right))
            return new Change(body.substring(0,start-left.length())+selected+body.substring(end+right.length()),end-left.length());
        if(selected.length()>=left.length()+right.length()&&selected.startsWith(left)&&selected.endsWith(right)){
            String plain=selected.substring(left.length(),selected.length()-right.length());return new Change(body.substring(0,start)+plain+body.substring(end),start+plain.length());
        }
        return changed(body,body.substring(0,start)+left+selected+right+body.substring(end),start+left.length()+selected.length(),focus);
    }
    private static final class Styled extends StyleSpan {Styled(int value){super(value);}}
    private static final class Underlined extends UnderlineSpan{}
    private static final class Struck extends StrikethroughSpan{}
    private static final class Highlighted extends BackgroundColorSpan{Highlighted(){super(0x66FFC107);}}
    private static final class Ink extends ForegroundColorSpan{Ink(int color){super(color);}}
    private static final class Sized extends RelativeSizeSpan{Sized(float size){super(size);}}
    private static void span(Editable text,Object value,int start,int end){if(start<end)text.setSpan(value,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    private static void removeOwned(Editable text,Class<?> type){for(Object span:text.getSpans(0,text.length(),type))text.removeSpan(span);}
    /** Call after checklist decoration. Only spans owned by this class are removed. */
    public static void decorate(Editable text,int textColor,int mutedColor){
        if(text==null)return;
        for(Class<?> type:new Class<?>[]{Styled.class,Underlined.class,Struck.class,Highlighted.class,Ink.class,Sized.class})removeOwned(text,type);
        String body=text.toString();String[] patterns={"\\*\\*(.+?)\\*\\*","(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)","~~(.+?)~~","<u>(.+?)</u>","==(.+?)=="};
        for(int kind=0;kind<patterns.length;kind++){
            Matcher m=Pattern.compile(patterns[kind]).matcher(body);
            while(m.find())if(!codeAt(body,m.start(),m.end())){
                Object value=kind==0?new Styled(Typeface.BOLD):kind==1?new Styled(Typeface.ITALIC):kind==2?new Struck():kind==3?new Underlined():new Highlighted();
                span(text,value,m.start(1),m.end(1));span(text,new Ink(mutedColor),m.start(),m.start(1));span(text,new Ink(mutedColor),m.end(1),m.end());
            }
        }
        for(Line line:lines(body))if(!line.code){String value=body.substring(line.start,line.end);int indent=indentEnd(value);Matcher h=HEADING.matcher(value.substring(indent));
            if(h.find()){int start=line.start+indent+h.end();span(text,new Styled(Typeface.BOLD),start,line.end);span(text,new Sized(h.group(1).length()==1?1.5f:h.group(1).length()==2?1.3f:1.15f),start,line.end);span(text,new Ink(textColor),start,line.end);span(text,new Ink(mutedColor),line.start+indent,start);}
        }
    }
    /** 上游渲染器只认 h1–h3 和无序列表；h4–h6、引用、分隔线、有序列表先转成等价 HTML。 */
    /** 上游渲染器只认 h1-h3 和无序列表；h4-h6、引用、分隔线、有序列表先转成等价 HTML。 */
    private static String previewBlocks(String escaped){
        StringBuilder out=new StringBuilder();int previous=0;
        for(Line line:lines(escaped)){
            out.append(escaped,previous,line.start);previous=line.end;
            String value=escaped.substring(line.start,line.end);
            if(line.code){out.append(value);continue;}
            Matcher h=HEADING.matcher(value);
            if(h.find()&&h.group(1).length()>3)value="<big><b><font color='#000000'>"+value.substring(h.end())+"</font></b></big>";
            if(value.startsWith("> ")){
                value="<i><font color='#8A8F98'>"+value.substring(2)+"</font></i>";
            }
            if(HR.matcher(value).matches())value="─────";
            Matcher ol=ORDERED.matcher(value);
            if(ol.find())value=ol.group(1)+ol.group(2)+ol.group(3)+" "+(ol.group(4)==null?"":ol.group(4));
            out.append(value);
        }
        return out.append(escaped.substring(previous)).toString();
    }
    /** Apply to already HTML-escaped text before the Markor filter. Enables only exact safe underline/highlight pairs. */
    public static String previewExtras(String escaped){
        return decorateLines(previewBlocks(escaped));
    }

    /** 逐行启用安全的下划线/高亮标记；块级转换已经做完，这里只处理行内。 */
    private static String decorateLines(String escaped){
        StringBuilder out=new StringBuilder();int previous=0;
        for(Line line:lines(escaped)){
            out.append(escaped,previous,line.start);String value=escaped.substring(line.start,line.end);
            if(!line.code)value=value.replaceAll("&lt;u&gt;([^\\n]+?)&lt;/u&gt;","<u>$1</u>")
                    .replaceAll("==([^\\n]+?)==","<afshighlight style='background-color:#FFF3B0'>$1</afshighlight>");
            out.append(value);previous=line.end;
        }return out.append(escaped.substring(previous)).toString();
    }
    /** Android Html ignores CSS backgrounds; handle our escaped-source-only highlight tag explicitly. */
    public static Spanned previewRich(String html){
        final java.util.ArrayDeque<Integer> starts=new java.util.ArrayDeque<>();
        return android.text.Html.fromHtml(html,android.text.Html.FROM_HTML_MODE_COMPACT,null,(opening,tag,output,reader)->{
            if(!"afshighlight".equalsIgnoreCase(tag))return;
            if(opening)starts.push(output.length());else if(!starts.isEmpty())span(output,new Highlighted(),starts.pop(),output.length());
        });
    }
}
