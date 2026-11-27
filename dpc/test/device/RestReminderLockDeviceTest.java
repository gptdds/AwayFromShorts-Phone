package com.afs.guard;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.util.Map;

/** Run only on an isolated emulator; restores all reminder preferences. */
public final class RestReminderLockDeviceTest extends Instrumentation {
    private int checks;
    private void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private void shell(String command) throws Exception {
        try (java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            byte[] bytes = new byte[1024];
            while (in.read(bytes) != -1) { }
        }
    }
    private int lockState() {
        return ((ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE)).getLockTaskModeState();
    }
    private void waitFocus(Activity activity) throws Exception {
        for (int i=0; i<100 && !activity.hasWindowFocus(); i++) Thread.sleep(50);
        check(activity.hasWindowFocus(), "reminder has window focus");
    }
    private View confirmButton(View view) {
        if (view.getContentDescription()!=null && T.s("知道了").contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) {
                View found=confirmButton(group.getChildAt(i));
                if (found!=null) return found;
            }
        }
        return null;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        int status=0;
        Context context=getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences("afs_rest",0);
        Map<String,?> original=prefs.getAll();
        Activity host=null;
        ActivityMonitor monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
        try {
            check(Build.MODEL.toLowerCase().contains("sdk"), "isolated emulator required");
            check(Guard.isOwner(context), "device-owner fixture required");
            check(lockState()==ActivityManager.LOCK_TASK_MODE_NONE, "fixture starts unlocked");
            // Prevent the service's real schedule from racing with these explicit reminders.
            prefs.edit().putBoolean("on",false).putBoolean("confirm",true).commit();
            host=startActivitySync(new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->RestAlert.show(context,30));
            RestReminderActivity popup=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(popup!=null, "reminder opens");
            waitFocus(popup);
            check(lockState()==ActivityManager.LOCK_TASK_MODE_LOCKED, "reminder enters real lock-task mode");
            shell("input keyevent KEYCODE_HOME"); Thread.sleep(350);
            check(popup.hasWindowFocus(), "Home cannot switch away");
            shell("input keyevent KEYCODE_APP_SWITCH"); Thread.sleep(350);
            check(popup.hasWindowFocus(), "Recents cannot switch away");
            runOnMainSync(popup::onBackPressed);
            check(!popup.isFinishing(), "Back cannot bypass initial stay");
            View button=confirmButton(popup.getWindow().getDecorView());
            check(button!=null, "confirmation mode provides a close button");
            runOnMainSync(()->button.performClick());
            check(!popup.isFinishing(), "confirmation cannot bypass initial stay");
            runOnMainSync(()->RestAlert.show(context,45)); Thread.sleep(250);
            check(lockState()==ActivityManager.LOCK_TASK_MODE_LOCKED, "repeated intent preserves lock");
            // Confirm mode remains locked even after its minimum stay expires.
            Thread.sleep(ReminderStore.AUTO_DISMISS_MS+200);
            check(!popup.isFinishing() && lockState()==ActivityManager.LOCK_TASK_MODE_LOCKED,
                    "confirmation mode stays locked until confirmed");
            View latestButton=confirmButton(popup.getWindow().getDecorView());
            runOnMainSync(()->latestButton.performClick());
            Thread.sleep(500);
            check(popup.isFinishing(), "confirmation closes after minimum stay");
            check(lockState()==ActivityManager.LOCK_TASK_MODE_NONE, "confirmation releases lock");
            waitFocus(host);
            shell("input keyevent KEYCODE_HOME"); Thread.sleep(400);
            check(!host.hasWindowFocus(), "Home works again after dismissal");

            removeMonitor(monitor);
            monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
            prefs.edit().putBoolean("confirm",false).commit();
            runOnMainSync(()->RestAlert.show(context,30));
            RestReminderActivity automatic=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(automatic!=null, "automatic reminder opens");
            waitFocus(automatic);
            check(lockState()==ActivityManager.LOCK_TASK_MODE_LOCKED, "automatic mode locks switching");
            for (int i=0; i<190 && !automatic.isFinishing(); i++) Thread.sleep(100);
            check(automatic.isFinishing(), "automatic mode closes after 15 seconds");
            check(lockState()==ActivityManager.LOCK_TASK_MODE_NONE, "automatic close releases lock");

            removeMonitor(monitor);
            monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
            runOnMainSync(()->RestAlert.show(context,30));
            RestReminderActivity cleared=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(cleared!=null, "administrative-clear reminder opens");
            waitFocus(cleared);
            runOnMainSync(()->RestAlert.clear(context)); Thread.sleep(350);
            check(cleared.isFinishing() && lockState()==ActivityManager.LOCK_TASK_MODE_NONE,
                    "administrative clear releases lock immediately");

            // A reminder above another locking page must not unlock that page on close.
            Activity inheritedHost=host;
            final Activity fh=inheritedHost;
            // Bring the host Settings page truly back to the foreground before it locks itself;
            // REORDER_TO_FRONT only reshuffles an existing task, it does not guarantee resume.
            runOnMainSync(()->{ fh.getSystemService(android.app.admin.DevicePolicyManager.class)
                    .setLockTaskPackages(Guard.admin(context),new String[]{context.getPackageName()});
                fh.startActivity(new Intent(context,SettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_NEW_TASK)); });
            waitFocus(inheritedHost);
            // startLockTask is applied asynchronously by the system; poll (bounded) instead of
            // asserting immediately, otherwise a correct lock reads back as NONE too early.
            runOnMainSync(inheritedHost::startLockTask);
            for (int i=0; i<60 && lockState()!=ActivityManager.LOCK_TASK_MODE_LOCKED; i++) Thread.sleep(50);
            check(lockState()==ActivityManager.LOCK_TASK_MODE_LOCKED, "host owns a pre-existing lock");
            removeMonitor(monitor);
            monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
            runOnMainSync(()->RestAlert.show(context,30));
            RestReminderActivity inherited=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(inherited!=null, "reminder opens over existing lock");
            waitFocus(inherited);
            runOnMainSync(()->RestAlert.clear(context)); Thread.sleep(350);
            check(inherited.isFinishing(), "reminder closes over host-owned lock");
            check(lockState()==ActivityManager.LOCK_TASK_MODE_LOCKED, "dismissal preserves host-owned lock");
            waitFocus(inheritedHost);
            shell("input keyevent KEYCODE_HOME"); Thread.sleep(350);
            check(inheritedHost.hasWindowFocus(), "host-owned lock still blocks Home after reminder closes");
            runOnMainSync(inheritedHost::stopLockTask);
            for (int i=0; i<60 && lockState()!=ActivityManager.LOCK_TASK_MODE_NONE; i++) Thread.sleep(50);
            check(lockState()==ActivityManager.LOCK_TASK_MODE_NONE, "host can release its own lock");
            result.putString("stream","Rest reminder lock device: "+checks+" checks passed\n");
            status=-1;
        } catch (Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));
        } finally {
            removeMonitor(monitor);
            runOnMainSync(()->RestAlert.clear(context));
            if (host!=null) {
                Activity finalHost=host;
                runOnMainSync(()->{try { finalHost.stopLockTask(); } catch (RuntimeException ignored) { } finalHost.finish();});
            }
            SharedPreferences.Editor restore=prefs.edit().clear();
            for (Map.Entry<String,?> item:original.entrySet()) {
                Object value=item.getValue(); String key=item.getKey();
                if(value instanceof Boolean) restore.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer) restore.putInt(key,(Integer)value);
                else if(value instanceof Long) restore.putLong(key,(Long)value);
                else if(value instanceof String) restore.putString(key,(String)value);
                else if(value instanceof Float) restore.putFloat(key,(Float)value);
            }
            restore.commit();
            finish(status,result);
        }
    }
}
