package android.speech.tts;
import android.content.Context;
import java.util.*;
/** Controllable asynchronous engine boundary; no Android runtime or audio output. */
public class TextToSpeech {
 public interface OnInitListener {void onInit(int status);}
 public static final int SUCCESS=0,ERROR=-1,LANG_MISSING_DATA=-1,LANG_NOT_SUPPORTED=-2,QUEUE_FLUSH=0;
 public static final List<TextToSpeech> created=new ArrayList<>();
 public final List<String> words=new ArrayList<>();
 private final OnInitListener listener;
 public Locale locale;public int language=0,result=SUCCESS,stops,shutdowns;
 public TextToSpeech(Context c,OnInitListener l){listener=l;created.add(this);}
 public void complete(int status){listener.onInit(status);}
 public int setLanguage(Locale value){locale=value;return language;}
 public int setSpeechRate(float rate){return SUCCESS;}
 public int speak(String text,int queue,Object params,String id){if(queue!=QUEUE_FLUSH)throw new AssertionError("stale speech must not queue behind the current card");words.add(text);return result;}
 public int stop(){stops++;return SUCCESS;}
 public void shutdown(){shutdowns++;}
}
