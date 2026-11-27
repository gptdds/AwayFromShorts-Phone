package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Locale;
import org.json.JSONObject;

/** Disposable-emulator proof that passion tickets are independent of learning linkage. */
public final class LoveGateDeviceTest extends Instrumentation {
    private int checks;
    private Activity rewardActivity;

    private static void gateField(String name,Object value) throws Exception {
        Field field=StudyGate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null,value);
    }

    private static void remoteSnapshot(Context context,boolean done) throws Exception {
        Calendar now=GuardClock.now(context);
        JSONObject snapshot=new JSONObject(StudyStore.snapshotJson(context));
        snapshot.put("allDone",done);
        snapshot.put("source","pc");
        gateField("cached",StudyGate.parse(snapshot.toString(),
                new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(now.getTime()),now.getTimeInMillis()));
    }

    private void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static byte[] read(File file) throws Exception {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void restore(File file, byte[] bytes) throws Exception {
        if (bytes == null) {
            if (file.exists()) checkDelete(file);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static void checkDelete(File file) {
        if (!file.delete()) throw new AssertionError("cannot restore fixture file " + file);
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor out = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) out.putBoolean(key, (Boolean) value);
            else if (value instanceof String) out.putString(key, (String) value);
            else if (value instanceof Integer) out.putInt(key, (Integer) value);
            else if (value instanceof Long) out.putLong(key, (Long) value);
            else if (value instanceof Float) out.putFloat(key, (Float) value);
            else if (value instanceof Set) out.putStringSet(key, (Set<String>) value);
        }
        if (!out.commit()) throw new AssertionError("cannot restore fixture preferences");
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Context context = getTargetContext();
        File study = new File(context.getFilesDir(), "study.json");
        File testCards = new File(context.getCacheDir(), "love-gate-device.json");
        File testQuiz = new File(context.getCacheDir(), "love-gate-quiz.json");
        File testPhoto = new File(context.getCacheDir(), "love-gate-photo.jpg");
        SharedPreferences links = context.getSharedPreferences("afs_learning_link", 0);
        Map<String, ?> oldLinks = links.getAll();
        Bundle result = new Bundle();
        byte[] oldStudy = null;
        int code = Activity.RESULT_OK;
        String message = "";
        try {
            check(android.os.Build.FINGERPRINT.contains("generic")
                    || android.os.Build.MODEL.contains("sdk_gphone"), "disposable emulator required");
            oldStudy = read(study);
            if (study.exists()) checkDelete(study);
            check(links.edit().clear().commit(), "learning-link fixture cleared");

            if (testCards.exists()) checkDelete(testCards);
            CardStore.setTestFile(testCards);
            if(testQuiz.exists())checkDelete(testQuiz);
            QuizStore.setTestFile(testQuiz);
            QuizStore.commit(context,new QuizData());
            StudyGate.invalidate(context);

            StudyStore.G goal = new StudyStore.G();
            goal.id = "qa-love-gate";
            goal.name = "QA Love Gate";
            goal.total = 1;
            StudyStore.upsert(context, goal);

            check(CardStore.canUse(context, GuardClock.now(context)),
                    "unfinished goal does not gate passion ticket use");
            check(!links.getBoolean("bind_target_reward", false),
                    "reward binding starts unbound");
            check(links.edit().putBoolean("bind_target_reward", true).commit(),
                    "reward binding fixture enabled");
            StudyGate.invalidate(context);
            check(!CardStore.canUse(context, GuardClock.now(context)),
                    "bound rewards stay blocked while goals are unfinished");
            check(links.edit().putBoolean("bind_target_reward", false).commit(),
                    "reward binding fixture cleared");
            StudyGate.invalidate(context);
            check(CardStore.canUse(context, GuardClock.now(context)),
                    "unbound rewards remain available");
            CardStore.saveLove(context, null, "QA Love", 15);
            String cardId = CardStore.snapshot(context).love().optJSONObject(0).optString("id");
            boolean noPhotoRejected = false;
            try { CardStore.claim(context, cardId); }
            catch (IllegalArgumentException expected) { noPhotoRejected = true; }
            check(noPhotoRejected, "claim without a photo is rejected");
            try (FileOutputStream out = new FileOutputStream(testPhoto)) {
                out.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
            }
            check(CardStore.claim(context, cardId, testPhoto.getAbsolutePath()),
                    "unfinished goal still allows earning today's ticket after a photo");
            check(CardStore.snapshot(context).tickets(cardId) == 1, "earned ticket remains available");
            long claimedAt = GuardClock.now(context).getTimeInMillis();
            check(CardStore.snapshot(context).availableTickets(cardId, claimedAt) == 0,
                    "earned ticket is locked during its first 24 hours");
            boolean cooldownRejected = false;
            try { CardStore.use(context, cardId); }
            catch (IllegalStateException expected) { cooldownRejected = true; }
            check(cooldownRejected, "using a ticket before 24 hours is rejected");
            CardData earned = CardStore.snapshot(context);
            MemoryData.put(earned.earned().optJSONObject(0), "at", claimedAt - CardData.CLAIM_COOLDOWN_MS);
            try (FileOutputStream out = new FileOutputStream(testCards)) {
                out.write(earned.db.toString().getBytes("UTF-8"));
            }
            CardStore.setTestFile(testCards);
            long before=GuardClock.now(context).getTimeInMillis();
            check(CardStore.use(context, cardId) == 15, "unfinished goal still allows spending the ticket");
            check(CardStore.snapshot(context).tickets(cardId) == 0, "spent ticket is consumed exactly once");
            check(CardStore.rewardUntil(context, before) > before, "ticket still opens its unfreeze window");

            LearningLink.configureQuiz(context,true,1);
            StudyGate.learningPending(context,GuardClock.now(context));
            check(CardStore.canUse(context,GuardClock.now(context)),
                    "unfinished quiz does not gate spending");
            LearningLink.configureQuiz(context,false,1);

            remoteSnapshot(context,false);
            check(CardStore.canUse(context,GuardClock.now(context)),
                    "unfinished cached snapshot does not gate spending");
            Intent homeIntent=new Intent(context,CardManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            rewardActivity=startActivitySync(homeIntent);
            waitForIdleSync();
            runOnMainSync(()->{
                View root=rewardActivity.getWindow().getDecorView();
                View homeUse=root.findViewWithTag("reward_use_home");
                check(homeUse!=null&&homeUse.isEnabled(),"card management home shows the use button below the balance");
            });
            runOnMainSync(()->rewardActivity.finish());
            rewardActivity=null;
            Intent intent=new Intent(context,CardManagementActivity.class);
            intent.putExtra("category","love").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            rewardActivity=startActivitySync(intent);
            waitForIdleSync();
            runOnMainSync(()->{
                View root=rewardActivity.getWindow().getDecorView();
                View use=root.findViewWithTag("reward_use");
                check(use!=null&&use.isEnabled(),"reward screen keeps its use button enabled");
                check(root.findViewWithTag("reward_blocked")==null,"reward screen shows no learning gate hint");
            });
            runOnMainSync(()->{
                rewardActivity.finish();
            });
            rewardActivity=null;

            CardData cards=CardStore.snapshot(context);
            cards.db.getJSONObject("summary").put("cursor","2000-01-03");
            try(FileOutputStream out=new FileOutputStream(testCards)){
                out.write(cards.db.toString().getBytes("UTF-8"));
            }
            CardStore.setTestFile(testCards);
            check(CardStore.canUse(context,GuardClock.now(context)),
                    "overdue summary does not gate spending");

            SharedPreferences quotaPrefs=context.getSharedPreferences(DailyQuotaStore.SP,0);
            Map<String,?> oldQuota=quotaPrefs.getAll();
            boolean hadUsage=AppUsageQuota.permission(context);
            Field permissionAt=AppUsageQuota.class.getDeclaredField("permissionAt");
            permissionAt.setAccessible(true);
            long oldPermissionAt=permissionAt.getLong(null);
            try{
                if(!hadUsage){
                    getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS allow").close();
                    permissionAt.setLong(null,0L);
                    long until=android.os.SystemClock.elapsedRealtime()+3000;
                    while(!AppUsageQuota.permission(context)&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);
                }
                check(AppUsageQuota.permission(context),"usage access available for quota penalty fixture");
                String penaltyPkg="com.afs.quota.penalty";
                JSONObject penaltyGroup=new JSONObject().put("id","penalty").put("name","Penalty")
                        .put("minutes",60).put("apps",new org.json.JSONArray().put(penaltyPkg));
                JSONObject penaltyConfig=new JSONObject().put("enabled",true)
                        .put("groups",new org.json.JSONArray().put(penaltyGroup));
                JSONObject staleUsage=new JSONObject().put("date","2000-01-01").put("lastAt",0L).put("since",0L)
                        .put("targets",new JSONObject().put(penaltyPkg,123456L));
                check(quotaPrefs.edit().clear().putString("config",penaltyConfig.toString())
                        .putString("usage",staleUsage.toString()).commit(),"stale quota ledger fixture saved");
                int firstCells=DailyQuotaStore.addTabooQuotaPenalty(context,30);
                JSONObject rolled=new JSONObject(quotaPrefs.getString("usage","{}"));
                String today=DayBoundary.key(context,GuardClock.now(context).getTimeInMillis());
                check(firstCells==1&&today.equals(rolled.optString("date")),
                        "quota penalty rolls a stale ledger onto the current day cells="+firstCells
                                +" today="+today+" usage="+rolled);
                check(rolled.optJSONObject("targets").optLong(penaltyPkg)==1800000L,
                        "quota penalty writes thirty minutes to the current-day ledger");
                check(DailyQuotaStore.addTabooQuotaPenalty(context,30)==1
                                &&new JSONObject(quotaPrefs.getString("usage","{}")).optJSONObject("targets").optLong(penaltyPkg)==3600000L,
                        "quota penalty can be stacked a second time");
            }finally{
                restore(quotaPrefs,oldQuota);
                if(!hadUsage)getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS default").close();
                permissionAt.setLong(null,oldPermissionAt);
                StudyGate.invalidate(context);
            }
            message = "Reward independence device: " + checks + " checks passed\n";
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            message = "Reward independence device failed after " + checks + " checks\n" + android.util.Log.getStackTraceString(error);
        } finally {
            if(rewardActivity!=null)runOnMainSync(()->rewardActivity.finish());
            CardStore.setTestFile(null);
            QuizStore.setTestFile(null);
            if(testQuiz.exists())testQuiz.delete();
            if(testPhoto.exists())testPhoto.delete();
            if (testCards.exists()) testCards.delete();
            try {
                restore(study, oldStudy);
                restore(links, oldLinks);
                StudyGate.resetRemote(context);
            } catch (Throwable error) {
                code = Activity.RESULT_CANCELED;
                message += "\nFixture restore failed\n" + android.util.Log.getStackTraceString(error);
            }
        }
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, message);
        finish(code, result);
    }
}
