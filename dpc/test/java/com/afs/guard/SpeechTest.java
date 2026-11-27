package com.afs.guard;

/**
 * 离线回归：发音取词（哪些卡面该给英音/美音按钮）与底栏显示规则。
 *
 * 这两条都是纯逻辑，不依赖 Android 运行时：
 *  取词错一个字符，按钮就会读错音；底栏规则错一条，子页面就会多出主标签栏。
 */
public final class SpeechTest {
    private static int count;
    private static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    private static void eq(Object want, Object got, String message) {
        count++; if (want == null ? got != null : !want.equals(got)) throw new AssertionError(message + ": want " + want + " got " + got);
    }

    public static void main(String[] args) {
        /* 取词：普通单词 */
        eq("suppress", Speech.latinWord("suppress"), "plain word");
        eq("household", Speech.latinWord("household"), "plain word 2");
        /* 整句里的英文 */
        eq("The quick brown fox", Speech.latinWord("The quick brown fox"), "phrase");
        /* 中文卡没有可读内容 */
        eq("", Speech.latinWord("压制，镇压；禁止发表"), "chinese only");
        eq("", Speech.latinWord(""), "empty");
        eq("", Speech.latinWord(null), "null");
        /* 音标转写不是单词，不该朗读 */
        eq("", Speech.latinWord("ˈsəˈpres"), "ipa");
        eq("", Speech.latinWord("/həʊˈseɪt/"), "ipa slashes");
        /* 撇号和连字符属于单词内部 */
        eq("don't stop", Speech.latinWord("don't stop"), "apostrophe");
        eq("well-known", Speech.latinWord("well-known"), "hyphen");
        /* 中英混排取英文部分 */
        eq("suppress", Speech.latinWord("suppress 压制"), "mixed");
        Speech.CardAutoplay auto=new Speech.CardAutoplay();
        auto.show("card-a|day1","suppress");
        eq("",auto.take("card-a|day1",false),"hidden card never queues pronunciation");
        eq("suppress",auto.take("card-a|day1",true),"visible word plays automatically");
        eq("",auto.take("card-a|day1",true),"same card does not repeat after focus/resume");
        auto.show("card-a|day1","suppress");
        eq("",auto.take("card-a|day1",true),"redraw does not repeat the same word");
        auto.show("card-b|day1","household");
        eq("",auto.take("card-a|day1",true),"stale card callback cannot read the next card");
        eq("household",auto.take("card-b|day1",true),"next card reads its own word");
        auto.show("card-c|day1","cancelled");auto.clear();
        eq("",auto.take("card-c|day1",true),"leaving a page cancels queued pronunciation");
        auto.show("card-d|day1","");eq("",auto.take("card-d|day1",true),"non-word card stays silent");
        auto.show("card-e|day1","manual");auto.markSpoken("card-e|day1");
        eq("",auto.take("card-e|day1",true),"manual replay suppresses delayed autoplay");
        Speech.CardAutoplay recreated=new Speech.CardAutoplay();recreated.markSpoken(auto.saved());recreated.show("card-e|day1","manual");
        eq("",recreated.take("card-e|day1",true),"recreation preserves pronunciation receipt");
        auto.advance();auto.show("card-e|day1","manual");eq("manual",auto.take("card-e|day1",true),"new learning presentation can pronounce the same card again");
        auto.show("card-e|day2","manual");eq("manual",auto.take("card-e|day2",true),"next day's card is a new presentation");

        /* 状态码 */
        check(Speech.OK == 0 && Speech.NO_TEXT == 1 && Speech.NO_ENGINE == 2 && Speech.NO_VOICE == 3, "status codes");

        /* 底栏规则：主页面挂，子页面不挂 */
        check(NavPolicy.memoryShowsNav(false, "home"), "memory home shows nav");
        check(!NavPolicy.memoryShowsNav(true, "tools"), "memory management hides nav");
        check(!NavPolicy.memoryShowsNav(false, "editor"), "memory editor hides nav");
        check(!NavPolicy.memoryShowsNav(false, "stats"), "memory stats hides nav");
        check(!NavPolicy.memoryShowsNav(false, "review"), "memory review hides nav");
        check(NavPolicy.quizShowsNav(false, "home"), "quiz home shows nav");
        check(!NavPolicy.quizShowsNav(true, "home"), "quiz management hides nav");
        check(!NavPolicy.quizShowsNav(false, "bank"), "quiz bank hides nav");
        check(NavPolicy.checkinShowsNav(false, null), "checkin home shows nav");
        check(NavPolicy.checkinShowsNav(false, ""), "checkin home shows nav 2");
        check(!NavPolicy.checkinShowsNav(false, "taboo"), "taboo hides nav");
        check(!NavPolicy.checkinShowsNav(false, "manage"), "manage hides nav");
        check(!NavPolicy.checkinShowsNav(false, "appearance"), "appearance hides nav");
        check(!NavPolicy.checkinShowsNav(true, null), "card management hides nav");

        System.out.println("Speech and nav policy: " + count + " assertions passed");
    }
}
