package com.afs.guard;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * 单词发音：调用系统 TTS，英音（en-GB）与美音（en-US）两种口音。
 *
 * 引擎初始化是异步的：init 完成之前点的发音先记下来，ready 之后补播一次。
 * 不走网络，用的是系统已装的语音包；缺对应口音的语音包时返回 NO_VOICE，
 * 交界面提示，不静默失败、也不偷偷换成另一个口音。
 */
final class Speech {
    /** 正常发音（或已排队，等引擎就绪后补播）。 */
    static final int OK = 0;
    /** 没有可朗读的内容。 */
    static final int NO_TEXT = 1;
    /** 系统没有可用的 TTS 引擎。 */
    static final int NO_ENGINE = 2;
    /** 引擎在，但缺这个口音的语音包。 */
    static final int NO_VOICE = 3;

    private static TextToSpeech engine;
    private static boolean ready, broken;
    private static String pending;
    private static boolean pendingUk;
    private static long engineGeneration;

    private Speech() { }

    /** 读一个词：uk=true 英音，false 美音。 */
    static int speak(Context c, String text, boolean uk) {
        String say = text == null ? "" : text.trim();
        if (say.isEmpty()) return NO_TEXT;
        synchronized (Speech.class) {
            if (broken) return NO_ENGINE;
            if (!ready) {
                pending = say; pendingUk = uk;
                start(c);
                return OK;
            }
            return utter(say, uk);
        }
    }

    /** Cancel queued/current speech without paying engine initialization again for the next card. */
    static void stop() {
        synchronized (Speech.class) {
            pending = null;
            if (engine != null) try { engine.stop(); } catch (RuntimeException ignored) { }
        }
    }

    /** Presentation-only state: it never reads or writes learning/check-in records. */
    static final class CardAutoplay {
        private String card="", word="", spoken="";
        void show(String id,String text){card=id==null?"":id;word=text==null?"":text;}
        String take(String current,boolean visible){
            if(!visible||card.isEmpty()||word.isEmpty()||!card.equals(current)||card.equals(spoken))return "";
            spoken=card;return word;
        }
        void clear(){card="";word="";}
        void advance(){clear();spoken="";}
        void markSpoken(String id){spoken=id==null?"":id;}
        String saved(){return spoken;}
    }

    /** 释放引擎；下次发音会重新初始化。 */
    static void release() {
        synchronized (Speech.class) {
            engineGeneration++;
            if (engine != null) {
                try { engine.stop(); engine.shutdown(); } catch (RuntimeException ignored) { }
                engine = null;
            }
            ready = false; broken = false; pending = null;
        }
    }

    /** 引擎是否已就绪（离线回归与自检用）。 */
    static boolean ready() { synchronized (Speech.class) { return ready; } }

    private static void start(Context c) {
        if (engine != null || broken || c == null) return;
        final long generation=++engineGeneration;
        try {
            engine = new TextToSpeech(c.getApplicationContext(), status -> {
                synchronized (Speech.class) {
                    if(generation!=engineGeneration)return;
                    if (status != TextToSpeech.SUCCESS) {
                        broken=true;pending=null;
                        if(engine!=null)try{engine.shutdown();}catch(RuntimeException ignored){}
                        engine=null;return;
                    }
                    ready = true;
                    if (pending != null) {
                        String text = pending; boolean uk = pendingUk; pending = null;
                        utter(text, uk);
                    }
                }
            });
        } catch (RuntimeException e) { broken = true; pending = null; engine = null; }
    }

    private static int utter(String say, boolean uk) {
        if (engine == null) return NO_ENGINE;
        Locale locale = uk ? Locale.UK : Locale.US;
        int r;
        try { r = engine.setLanguage(locale); } catch (RuntimeException e) { return NO_ENGINE; }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return NO_VOICE;
        try {
            engine.setSpeechRate(0.95f);
            if(engine.speak(say, TextToSpeech.QUEUE_FLUSH, null, "afs-" + System.currentTimeMillis())==TextToSpeech.ERROR)return NO_ENGINE;
        } catch (RuntimeException e) { return NO_ENGINE; }
        return OK;
    }

    /**
     * 从一段文字里挑出可朗读的英文：取最长的拉丁字母片段（单词或词组）。
     * 中文卡、纯音标卡返回空串，界面据此不显示发音按钮。
     */
    static String latinWord(String text) {
        if (text == null) return "";
        if (phonetic(text)) return "";
        String best = "";
        int i = 0, n = text.length();
        while (i < n) {
            if (latin(text.charAt(i))) {
                int j = i;
                while (j < n && (latin(text.charAt(j))
                        || text.charAt(j) == '\'' || text.charAt(j) == '-'
                        || (text.charAt(j) == ' ' && j + 1 < n && latin(text.charAt(j + 1))))) j++;
                String word = text.substring(i, j).trim();
                if (word.length() > best.length()) best = word;
                i = j;
            } else i++;
        }
        return best;
    }

    /**
     * 出现国际音标专用符号就当是音标转写：里面那些拉丁字母是拼出来的读音，
     * 不是单词，读出来是错的，直接不提供发音。
     */
    private static boolean phonetic(String text) {
        for (int i = 0; i < text.length(); i++) {
            if ("ˈˌəɪʊɔæʌθðŋʃʒːɒɜ".indexOf(text.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static boolean latin(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
