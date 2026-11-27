package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Pure Java integration tests: exercise the real quiz and memory adapters, not a scheduler stub. */
public final class QuizReviewTest {
    private static final long NOW = 1792893600000L;
    private static final long MINUTE = 60000L;
    private static int assertions;
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void reject(Runnable action, String message) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void run(String name, Runnable test) {
        try { test.run(); }
        catch (AssertionError | RuntimeException failure) {
            failures.add(name + ": " + failure);
        }
    }
    private static JSONArray ids(String... values) {
        JSONArray a = new JSONArray();
        for (String value : values) a.put(value);
        return a;
    }
    private static JSONObject question(String id, String type) {
        JSONObject q = new JSONObject();
        QuizData.put(q, "id", id); QuizData.put(q, "subject", "高等数学");
        QuizData.put(q, "stem", "Recall " + id); QuizData.put(q, "type", type);
        QuizData.put(q, "answer", "A");
        if ("single".equals(type) || "multiple".equals(type))
            QuizData.put(q, "options", ids("first", "second", "third"));
        return q;
    }
    private static QuizData quiz(String type, String... values) {
        JSONArray rows = new JSONArray();
        for (String id : values) rows.put(question(id, type));
        return new QuizData().prepareImport(rows.toString(), "高数", "recall-test").candidate;
    }
    private static void stateEquals(MemoryScheduler.State expected, MemoryScheduler.State actual, String context) {
        check(expected.state == actual.state && expected.step == actual.step, context + " state/step");
        check(expected.reps == actual.reps && expected.lapses == actual.lapses, context + " reps/lapses");
        check(expected.last == actual.last && expected.due == actual.due, context + " timestamps");
        check(Math.abs(expected.stability - actual.stability) < 1e-12, context + " stability");
        check(Math.abs(expected.difficulty - actual.difficulty) < 1e-12, context + " difficulty");
    }
    private static String current(QuizData q) { return q.current() == null ? "" : q.current().optString("id"); }
    private static String next(MemoryData m, long now) {
        JSONObject card = MemoryFlashcards.next(m, now);
        return card == null ? "" : card.optString("id");
    }
    private static void seed(JSONObject p, int state, int step, long due, double stability, long last) {
        QuizData.put(p, "reps", 4); QuizData.put(p, "state", state); QuizData.put(p, "step", step);
        QuizData.put(p, "due", due); QuizData.put(p, "stability", stability);
        QuizData.put(p, "difficulty", 5.0); QuizData.put(p, "last", last); QuizData.put(p, "lapses", 1);
    }

    private static final class Pair {
        MemoryData memory = new MemoryData();
        QuizData quiz;
        final String deck = memory.activeDecks().optJSONObject(0).optString("id");
        Pair(String... values) {
            quiz = quiz("single", values);
            for (String id : values) {
                JSONObject card = MemoryData.newCard(deck, "qa", "Recall " + id, "A", "", "");
                MemoryData.put(card, "id", id); memory.saveCard(card);
            }
        }
        void old(String id, int state, int step, long due, double stability, long last) {
            seed(memory.card(id), state, step, due, stability, last);
            JSONObject p = new JSONObject(); seed(p, state, step, due, stability, last);
            QuizData.put(p, "attempts", 4); QuizData.put(p, "correct", 3);
            QuizData.put(quiz.db.optJSONObject("progress"), id, p);
        }
        void configure(int count, int fresh, long now) { configure(count, fresh, .9, 36500, now); }
        void configure(int count, int fresh, double retention, int maxInterval, long now) {
            MemoryData.put(memory.deck(deck), "requestRetention", retention);
            MemoryData.put(memory.deck(deck), "maxInterval", maxInterval);
            MemoryFlashcards.configure(memory, ids(deck), count, fresh, now);
            QuizReview.configure(quiz, ids("高数"), count, fresh, retention, maxInterval, now);
            quiz.startReview(now);
            queues(now);
        }
        void queues(long now) {
            check(MemoryFlashcards.session(memory, now).optJSONArray("ids").toString().equals(
                    QuizReview.session(quiz, now).optJSONArray("ids").toString()), "adapter queue parity");
            JSONObject ready = QuizReview.next(quiz, now);
            check(next(memory, now).equals(ready == null ? "" : ready.optString("id")), "adapter next parity");
            MemoryFlashcards.Progress m = MemoryFlashcards.progress(memory, now);
            QuizReview.Progress q = QuizReview.progress(quiz, now);
            check(m.total == q.total && m.done == q.done && m.nextDue == q.nextDue
                    && m.reviewedToday == q.processed, "adapter progress parity");
        }
        void rate(String id, int rating, long now) {
            check(id.equals(current(quiz)) && id.equals(next(memory, now)), "both adapters show " + id);
            String memoryBefore = memory.db.toString(), quizBefore = quiz.db.toString();
            MemoryScheduler.State predicted = memory.previewRating(id, rating, now);
            stateEquals(predicted, QuizReview.preview(quiz, id, rating, now), "preview " + id);
            check(memoryBefore.equals(memory.db.toString()) && quizBefore.equals(quiz.db.toString()), "preview is read only");
            quiz.draft("A", "");
            check(quiz.grade(null, rating, now, id, MemoryData.date(now)), "correct objective answer");
            memory.rateFlashcard(id, rating, now, MemoryData.date(now));
            stateEquals(predicted, MemoryData.state(memory.card(id)), "actual memory " + id);
            stateEquals(predicted, MemoryData.state(quiz.progress(id)), "actual quiz " + id);
            queues(now);
        }
        void restore() {
            memory = new MemoryData(memory.db.toString()); quiz = new QuizData(quiz.db.toString());
        }
    }

    public static void main(String[] args) {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            run("new two remembered graduation", QuizReviewTest::newGraduation);
            run("all rating previews and actual states", QuizReviewTest::ratingParity);
            run("fuzzy review and restart", QuizReviewTest::fuzzyReview);
            run("forgotten minute steps", QuizReviewTest::forgottenSteps);
            run("risk ordering and future exclusion", QuizReviewTest::riskOrdering);
            run("daily admission caps and rerank", QuizReviewTest::dailyCaps);
            run("objective check and wrong handling", QuizReviewTest::objectiveHandling);
            run("subjective reveal and self assessment", QuizReviewTest::subjectiveHandling);
            run("duplicate stale id and date", QuizReviewTest::submissionGuards);
            run("restore checked and graded sessions", QuizReviewTest::sessionRestore);
            run("legacy projection and history", QuizReviewTest::legacyRetention);
            run("scheduled next resumes waiting repetition", QuizReviewTest::resumeWaiting);
        } finally { TimeZone.setDefault(previous); }
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " failing scenarios: " + failures);
        System.out.println("Quiz review: " + assertions + " assertions passed");
    }

    private static void newGraduation() {
        Pair p = new Pair("new"); p.configure(1, 1, NOW);
        p.rate("new", 3, NOW);
        check(p.quiz.progress("new").optInt("state") == MemoryScheduler.LEARNING, "first remembered stays learning");
        check(QuizReview.progress(p.quiz, NOW).done == 0 && QuizReview.progress(p.quiz, NOW).nextDue == NOW + 10 * MINUTE,
                "first remembered waits ten minutes and earns no completion");
        p.restore(); p.quiz.startReview(NOW + 10 * MINUTE); p.rate("new", 3, NOW + 10 * MINUTE);
        check(QuizReview.progress(p.quiz, NOW + 10 * MINUTE).done == 1, "second remembered graduates");
        check(p.quiz.progress("new").optInt("reps") == 2 && p.quiz.stats("")[0] == 2, "two real ratings recorded");
        check(QuizReview.progress(p.quiz, NOW + 10 * MINUTE).processed == 1, "repetitions count one distinct question");
        check(p.quiz.progress("new").optLong("due") > LearningRules.endOfDay(NOW), "graduation scheduled on a later local day");
    }

    private static void ratingParity() {
        for (int state : new int[]{-1, MemoryScheduler.LEARNING, MemoryScheduler.REVIEW, MemoryScheduler.RELEARNING}) {
            for (int rating = 1; rating <= 3; rating++) {
                Pair p = new Pair("parity");
                if (state >= 0) p.old("parity", state, state == MemoryScheduler.LEARNING ? 1 : 0,
                        NOW - MINUTE, 8.0, NOW - 5 * MemoryScheduler.DAY);
                p.configure(1, 1, .95, 2, NOW);
                p.rate("parity", rating, NOW);
                if (p.quiz.progress("parity").optInt("state") == MemoryScheduler.REVIEW)
                    check(p.quiz.progress("parity").optLong("due") <= LearningRules.endOfDay(NOW + 2 * MemoryScheduler.DAY),
                            "custom maximum interval applies to actual rating");
            }
        }
    }

    private static void fuzzyReview() {
        Pair p = new Pair("fuzzy");
        p.old("fuzzy", MemoryScheduler.REVIEW, 0, NOW - MINUTE, 10, NOW - 5 * MemoryScheduler.DAY);
        p.configure(1, 0, NOW); p.rate("fuzzy", 2, NOW);
        check(p.quiz.progress("fuzzy").optInt("state") == MemoryScheduler.RELEARNING, "fuzzy review enters relearning");
        check(QuizReview.progress(p.quiz, NOW).done == 0 && QuizReview.progress(p.quiz, NOW).nextDue == NOW + 15 * MINUTE,
                "fuzzy review requires fifteen minute confirmation");
        p.restore(); p.queues(NOW + 15 * MINUTE - 1);
        check(QuizReview.next(p.quiz, NOW + 15 * MINUTE - 1) == null, "confirmation unavailable before deadline");
        p.quiz.next(NOW + 15 * MINUTE); p.rate("fuzzy", 3, NOW + 15 * MINUTE);
        check(QuizReview.progress(p.quiz, NOW + 15 * MINUTE).done == 1, "confirmation finishes review");
        check(p.quiz.db.optJSONObject("review").optJSONObject("day").optJSONArray("newSeen").length() == 0,
                "old reviews do not consume fresh budget");
    }

    private static void forgottenSteps() {
        Pair fresh = new Pair("forgot-new"); fresh.configure(1, 1, NOW); fresh.rate("forgot-new", 1, NOW);
        check(QuizReview.progress(fresh.quiz, NOW).nextDue == NOW + MINUTE, "forgotten fresh waits one minute");
        fresh.quiz.next(NOW + MINUTE); fresh.rate("forgot-new", 3, NOW + MINUTE);
        check(QuizReview.progress(fresh.quiz, NOW + MINUTE).done == 0, "first remembered after forgetting still learning");
        fresh.quiz.next(NOW + 11 * MINUTE); fresh.rate("forgot-new", 3, NOW + 11 * MINUTE);
        check(QuizReview.progress(fresh.quiz, NOW + 11 * MINUTE).done == 1, "fresh forget then two remembers graduates");
        Pair old = new Pair("forgot-old");
        old.old("forgot-old", MemoryScheduler.REVIEW, 0, NOW - MINUTE, 10, NOW - 5 * MemoryScheduler.DAY);
        old.configure(1, 0, NOW); old.rate("forgot-old", 1, NOW);
        check(QuizReview.progress(old.quiz, NOW).nextDue == NOW + 10 * MINUTE, "forgotten review waits ten minutes");
        check(old.quiz.progress("forgot-old").optInt("lapses") == 2, "real forget adds one lapse");
        old.quiz.next(NOW + 10 * MINUTE); old.rate("forgot-old", 3, NOW + 10 * MINUTE);
        check(QuizReview.progress(old.quiz, NOW + 10 * MINUTE).done == 1, "remembered old review graduates relearning");
    }

    private static void riskOrdering() {
        Pair p = new Pair("strong", "weak", "learning", "future", "fresh-a", "fresh-b");
        p.old("strong", MemoryScheduler.REVIEW, 0, NOW - MINUTE, 10, NOW - 5 * MemoryScheduler.DAY);
        p.old("weak", MemoryScheduler.REVIEW, 0, NOW - MINUTE, 2, NOW - 5 * MemoryScheduler.DAY);
        p.old("learning", MemoryScheduler.LEARNING, 0, NOW + MINUTE, 1, NOW - 10 * MINUTE);
        p.old("future", MemoryScheduler.REVIEW, 0, NOW + 3 * MemoryScheduler.DAY, 10, NOW - MemoryScheduler.DAY);
        p.configure(5, 1, NOW);
        check(QuizReview.session(p.quiz, NOW).optJSONArray("ids").toString().equals(ids("learning", "weak", "strong", "fresh-a").toString()),
                "learning steps then forgetting risk then import order; future excluded and fresh capped");
        check("weak".equals(current(p.quiz)), "future minute step does not block ready review");
        p.rate("weak", 3, NOW);
        p.quiz.next(NOW + MINUTE);
        check("learning".equals(current(p.quiz)), "ready learning step takes precedence over untouched reviews");
        p.queues(NOW + MINUTE);
    }

    private static void dailyCaps() {
        Pair p = new Pair("a", "b", "c", "d"); p.configure(2, 1, NOW); p.rate("a", 3, NOW);
        p.configure(2, 1, NOW);
        check(QuizReview.session(p.quiz, NOW).optJSONArray("ids").toString().equals(ids("a").toString()),
                "rerank retains started learning and cannot reset new budget");
        p.configure(2, 2, NOW);
        check(QuizReview.session(p.quiz, NOW).optJSONArray("ids").toString().equals(ids("a", "b").toString()),
                "raising fresh cap admits only remaining daily room");
        p.rate("b", 1, NOW);
        p.restore(); p.configure(1, 0, NOW);
        check(QuizReview.session(p.quiz, NOW).optJSONArray("ids").length() == 2, "lowering caps preserves both started repetitions");
        p.configure(2, 4, NOW);
        check(QuizReview.session(p.quiz, NOW).optJSONArray("ids").length() == 2, "daily distinct cap prevents third admission");
        JSONObject day = p.quiz.db.optJSONObject("review").optJSONObject("day");
        check(day.optJSONArray("seen").length() == 2 && day.optJSONArray("newSeen").length() == 2, "daily ledgers survive restore and configure");
        Pair old = new Pair("old", "new-a", "new-b");
        old.old("old", MemoryScheduler.REVIEW, 0, NOW - MINUTE, 10, NOW - MemoryScheduler.DAY);
        old.configure(2, 1, NOW); old.rate("old", 3, NOW); old.configure(2, 1, NOW);
        check(QuizReview.session(old.quiz, NOW).optJSONArray("ids").toString().equals(ids("new-a").toString()),
                "old rating spends total budget while leaving one fresh admission");
        old.rate("new-a", 3, NOW); old.configure(2, 2, NOW);
        check(!MemoryData.contains(QuizReview.session(old.quiz, NOW).optJSONArray("ids"), "new-b"), "old plus fresh exhaust distinct total cap");
        Pair rollover = new Pair("today-a", "today-b", "tomorrow-a", "tomorrow-b");
        rollover.configure(2, 2, NOW); rollover.rate("today-a", 3, NOW);
        rollover.quiz.next(NOW); rollover.rate("today-b", 3, NOW);
        rollover.quiz.next(NOW + 10 * MINUTE); rollover.rate("today-a", 3, NOW + 10 * MINUTE);
        rollover.quiz.next(NOW + 10 * MINUTE); rollover.rate("today-b", 3, NOW + 10 * MINUTE);
        rollover.restore(); rollover.configure(2, 2, NOW + 10 * MINUTE);
        check(QuizReview.session(rollover.quiz, NOW).optJSONArray("ids").length() == 0, "finished distinct admissions still consume today's cap after restart");
        rollover.configure(2, 2, NOW + MemoryScheduler.DAY);
        check(QuizReview.session(rollover.quiz, NOW + MemoryScheduler.DAY).optJSONArray("ids").toString().equals(ids("tomorrow-a", "tomorrow-b").toString()),
                "local day rollover resets admission caps and excludes graduated future reviews");
    }

    private static void objectiveHandling() {
        for (int requested = 1; requested <= 3; requested++) {
            final int rating = requested;
            QuizData q = quiz("single", "wrong"); QuizReview.configure(q, ids("高数"), 1, 1, .9, 36500, NOW); q.startReview(NOW);
            q.draft("B", ""); String progress = q.db.optJSONObject("progress").toString(), days = q.db.optJSONObject("days").toString();
            q.checkAnswer(); q.checkAnswer();
            check(q.session().optBoolean("revealed") && !q.session().optBoolean("checkedCorrect"), "check reveals objective error");
            check(progress.equals(q.db.optJSONObject("progress").toString()) && days.equals(q.db.optJSONObject("days").toString()), "checking twice never grades");
            check(QuizReview.progress(q, NOW).processed == 0, "check does not consume daily cap");
            check(!q.grade(true, rating, NOW, "wrong", MemoryData.date(NOW)), "objective wrong ignores self override");
            check(q.progress("wrong").optInt("rating") == 1 && q.progress("wrong").optLong("due") == NOW + MINUTE,
                    "objective error forces forget regardless of requested button");
            check(q.progress("wrong").optBoolean("wrong") && q.progress("wrong").optInt("correct") == 0, "wrong history retained");
            reject(() -> q.grade(null, rating, NOW, "wrong", MemoryData.date(NOW)), "duplicate wrong submission rejected");
        }
        JSONObject multiple = question("partial", "multiple"); QuizData.put(multiple, "answer", "AC");
        QuizData multi = new QuizData().prepareImport(new JSONArray().put(multiple).toString(), "高数", "test").candidate;
        QuizReview.configure(multi, ids("高数"), 1, 1, .9, 36500, NOW); multi.startReview(NOW); multi.draft("A", ""); multi.checkAnswer();
        check(!multi.grade(null, 3, NOW, "partial", MemoryData.date(NOW)) && multi.progress("partial").optInt("rating") == 1,
                "partially correct multiple choice forces forget");
        multi.startReview(NOW + MINUTE); multi.draft("CA", ""); multi.checkAnswer();
        check(multi.session().optBoolean("checkedCorrect") && multi.grade(null, 2, NOW + MINUTE, "partial", MemoryData.date(NOW)),
                "unordered exact multiple choice answer accepts explicit fuzzy rating");
        check(multi.progress("partial").optInt("rating") == 2, "correct multiple choice honors chosen recall rating");
        QuizData judge = quiz("judge", "judge"); QuizReview.configure(judge, ids("高数"), 1, 1, .9, 36500, NOW);
        judge.startReview(NOW); judge.draft("B", ""); judge.checkAnswer();
        check(!judge.grade(null, 3, NOW, "judge", MemoryData.date(NOW)) && judge.progress("judge").optInt("rating") == 1,
                "incorrect judgment forces forget");
        QuizData legacy = quiz("single", "legacy-grade"); legacy.start(legacy.filter("高数", "", "all", ""), false);
        legacy.draft("A", ""); check(legacy.grade(null, NOW), "legacy grade overload remains available");
        check(legacy.progress("legacy-grade").optInt("rating") == 3, "legacy correct maps to remembered");
    }

    private static void subjectiveHandling() {
        for (String type : new String[]{"short", "solution"}) {
            QuizData q = quiz(type, "self"); QuizReview.configure(q, ids("高数"), 1, 1, .9, 36500, NOW); q.startReview(NOW);
            reject(() -> q.grade(true, 3, NOW, "self", MemoryData.date(NOW)), "subjective requires reveal");
            q.draft("", "my reasoning"); q.reveal();
            reject(() -> q.grade(null, 3, NOW, "self", MemoryData.date(NOW)), "subjective requires explicit self assessment");
            check(q.grade(true, 2, NOW, "self", MemoryData.date(NOW)), "subjective self assessment accepted");
            check(q.progress("self").optInt("rating") == 2 && q.progress("self").optLong("due") == NOW + 330000,
                    "subjective fuzzy uses same fresh schedule");
            q.startReview(NOW + 330000); q.reveal();
            check(!q.grade(false, 3, NOW + 330000, "self", MemoryData.date(NOW)), "negative self assessment records forget");
            check(q.progress("self").optInt("rating") == 1, "negative self assessment cannot earn remembered rating");
        }
    }

    private static void submissionGuards() {
        QuizData q = quiz("single", "a", "b"); QuizReview.configure(q, ids("高数"), 2, 2, .9, 36500, NOW); q.startReview(NOW); q.draft("A", "");
        String before = q.db.toString();
        reject(() -> q.grade(null, 3, NOW, "b", MemoryData.date(NOW)), "stale question id rejected");
        reject(() -> q.grade(null, 3, NOW, "a", MemoryData.date(NOW - MemoryScheduler.DAY)), "stale expected date rejected");
        reject(() -> q.grade(null, 4, NOW, "a", MemoryData.date(NOW)), "fourth button rejected");
        check(before.equals(q.db.toString()), "invalid submissions have no side effects");
        q.grade(null, 3, NOW, "a", MemoryData.date(NOW)); String graded = q.db.toString();
        reject(() -> q.grade(null, 3, NOW, "a", MemoryData.date(NOW)), "double click rejected");
        check(graded.equals(q.db.toString()), "duplicate submission has no side effects");
        q.next(NOW); q.draft("A", ""); before = q.db.toString();
        reject(() -> q.grade(null, 3, NOW, "a", MemoryData.date(NOW)), "previous question callback rejected after next");
        check(before.equals(q.db.toString()), "stale id cannot grade replacement question");
        reject(() -> q.grade(null, 3, NOW + MemoryScheduler.DAY, "b", MemoryData.date(NOW + MemoryScheduler.DAY)),
                "yesterday scheduled session rejected even with today's expected date");
        q.startReview(NOW + MemoryScheduler.DAY); q.draft("A", "");
        check(q.grade(null, 3, NOW + MemoryScheduler.DAY, current(q), MemoryData.date(NOW + MemoryScheduler.DAY)), "new day session can be graded");
    }

    private static void sessionRestore() {
        QuizData q = quiz("single", "persist"); QuizReview.configure(q, ids("高数"), 1, 1, .93, 7, NOW); q.startReview(NOW);
        q.draft("A", "saved draft"); q.checkAnswer(); q = new QuizData(q.db.toString());
        check(q.session().optBoolean("revealed") && q.session().optBoolean("checkedCorrect") && "saved draft".equals(q.session().optString("input")),
                "checked answer and draft survive JSON restore");
        check(q.stats("")[0] == 0 && q.progress("persist").optInt("reps") == 0, "restore of reveal does not invent rating");
        q.grade(null, 3, NOW, "persist", MemoryData.date(NOW)); final QuizData restored = new QuizData(q.db.toString());
        reject(() -> restored.grade(null, 3, NOW, "persist", MemoryData.date(NOW)), "restored graded session rejects duplicate");
        check(restored.stats("")[0] == 1 && QuizReview.progress(restored, NOW).processed == 1, "restore retains exactly one rating and daily admission");
        check(QuizReview.settings(restored).optDouble("requestRetention") == .93 && QuizReview.settings(restored).optInt("maxInterval") == 7,
                "scheduler settings persist");
    }

    private static void legacyRetention() {
        QuizData q = quiz("single", "legacy", "brand-new");
        JSONObject history = new JSONObject(); QuizData.put(history, "attempts", 23); QuizData.put(history, "correct", 17);
        QuizData.put(history, "wrong", true); QuizData.put(history, "star", true); QuizData.put(history, "id", "stale-embedded-id");
        QuizData.put(q.db.optJSONObject("progress"), "legacy", history);
        JSONObject day = new JSONObject(); QuizData.put(day, "attempts", 23); QuizData.put(day, "correct", 17);
        QuizData.put(q.db.optJSONObject("days"), "2026-10-01", day);
        String original = q.db.toString();
        List<JSONObject> items = QuizReview.items(q);
        check(original.equals(q.db.toString()), "legacy projection does not write scheduler state");
        check("legacy".equals(items.get(0).optString("id")), "projection normalizes id to question id");
        check(items.get(0).optInt("reps") > 0 && items.get(0).optLong("due") == 0, "legacy attempts project as due review");
        check(!q.progress("legacy").has("reps") && !q.progress("legacy").has("stability"), "legacy history gains no fabricated FSRS values");
        QuizReview.configure(q, ids("高数"), 1, 0, .9, 36500, NOW); q.startReview(NOW);
        check("legacy".equals(current(q)), "legacy attempts remain reviewable with fresh limit zero");
        Pair fresh = new Pair("legacy"); fresh.configure(1, 1, NOW);
        stateEquals(fresh.memory.previewRating("legacy", 3, NOW), QuizReview.preview(q, "legacy", 3, NOW),
                "first real legacy rating initializes as fresh scheduler state");
        q.draft("A", ""); q.grade(null, 3, NOW, "legacy", MemoryData.date(NOW));
        check(q.progress("legacy").optInt("reps") == 1 && q.progress("legacy").optInt("state") == MemoryScheduler.LEARNING,
                "historical attempts are not converted to prior successful recalls");
        q = new QuizData(q.db.toString());
        check(q.progress("legacy").optInt("attempts") == 24 && q.progress("legacy").optInt("correct") == 18
                && q.progress("legacy").optBoolean("star") && !q.progress("legacy").optBoolean("wrong"), "real rating preserves cumulative history and favorite");
        check(q.stats("2026-10-01")[0] == 23 && q.stats("2026-10-01")[1] == 17, "legacy daily totals retained exactly");
        check(q.db.optJSONObject("review").optJSONObject("day").optJSONArray("newSeen").length() == 0,
                "legacy review initialization does not consume fresh admission");
    }

    private static void resumeWaiting() {
        Pair p = new Pair("repeat"); p.configure(1, 1, NOW); p.rate("repeat", 3, NOW);
        p.quiz.next(NOW);
        check(p.quiz.current() == null, "no question shown while sole repetition waits");
        p.quiz.next(NOW + 10 * MINUTE - 1);
        check(p.quiz.current() == null, "next before deadline cannot advance waiting question");
        p.restore(); p.quiz.next(NOW + 10 * MINUTE);
        check("repeat".equals(current(p.quiz)), "next(now) resumes ready repetition after waiting and JSON restart");
        p.rate("repeat", 3, NOW + 10 * MINUTE);
    }
}
