package com.afs.guard;
import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.*;
public final class SpeechLifecycleTest {
 private static int count;
 private static void check(boolean ok,String why){count++;if(!ok)throw new AssertionError(why);}
 private static TextToSpeech last(){return TextToSpeech.created.get(TextToSpeech.created.size()-1);}
 public static void main(String[] args){
  Context c=new Context();Speech.release();
  check(Speech.speak(c,"first",false)==Speech.OK,"first card queues during engine startup");TextToSpeech a=last();
  Speech.speak(c,"second",false);check(a.words.isEmpty(),"initialization never speaks prematurely");
  a.complete(TextToSpeech.SUCCESS);check(a.words.equals(Arrays.asList("second")),"only the latest card speaks after delayed startup");
  check(Locale.US.equals(a.locale),"automatic pronunciation selects US English");
  Speech.stop();check(a.stops==1,"leaving study stops current speech");
  Speech.release();Speech.speak(c,"cancelled",false);TextToSpeech b=last();Speech.stop();b.complete(TextToSpeech.SUCCESS);
  check(b.words.isEmpty(),"pending startup speech is cancelled when leaving a page");
  Speech.release();Speech.speak(c,"old activity",false);TextToSpeech stale=last();Speech.release();
  Speech.speak(c,"current activity",false);TextToSpeech current=last();stale.complete(TextToSpeech.SUCCESS);
  check(!Speech.ready()&&stale.words.isEmpty(),"destroyed activity's initialization cannot claim the new engine");
  current.complete(TextToSpeech.SUCCESS);check(current.words.equals(Arrays.asList("current activity")),"current card survives the stale initialization callback");
  stale.complete(TextToSpeech.ERROR);check(Speech.ready(),"late failure from old engine cannot break current speech");
  current.language=TextToSpeech.LANG_MISSING_DATA;check(Speech.speak(c,"missing voice",false)==Speech.NO_VOICE,"missing English voice is reported");
  current.language=0;current.result=TextToSpeech.ERROR;check(Speech.speak(c,"rejected",false)==Speech.NO_ENGINE,"engine's rejected speak result is not reported as success");
  Speech.release();Speech.speak(c,"failed startup",false);TextToSpeech failed=last();failed.complete(TextToSpeech.ERROR);
  check(failed.shutdowns==1&&!Speech.ready(),"failed initialization releases engine resources");
  check(Speech.speak(c,"retry",false)==Speech.NO_ENGINE,"startup failure remains explicit");
  Speech.release();check(Speech.speak(c,"",false)==Speech.NO_TEXT,"empty cards do not initialize audio");
  System.out.println("Speech lifecycle: "+count+" checks passed");
 }
}
