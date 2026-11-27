package com.afs.guard;

import com.afs.guard.plugins.CheckinPlugin;
import com.afs.guard.plugins.DockPages;
import com.afs.guard.plugins.FreezePlugin;
import com.afs.guard.plugins.MemoryPlugin;
import com.afs.guard.plugins.QuizPlugin;

public final class AppPluginTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(DockPages.plugins().size() == 4, "four application plugins");
        check(DockPages.plugin(CheckinPlugin.ID) != null, "check-in plugin registered");
        check(DockPages.plugin(MemoryPlugin.ID) != null, "memory plugin registered");
        check(DockPages.plugin(QuizPlugin.ID) != null, "quiz plugin registered");
        check(DockPages.plugin(FreezePlugin.ID) != null, "freeze plugin registered");
        check(DockPages.plugin("missing") == null, "unknown plugin rejected");
        check(DockPages.plugins().get(0).id().equals(CheckinPlugin.ID), "check-in plugin listed first");
        check(DockPages.plugins().get(1).id().equals(MemoryPlugin.ID), "memory plugin listed second");
        check(DockPages.plugins().get(2).id().equals(QuizPlugin.ID), "quiz plugin listed third");
        check(DockPages.plugins().get(3).id().equals(FreezePlugin.ID), "freeze plugin listed fourth");

        check(DockPages.indexOfPlugin(CheckinPlugin.ID) == DockPages.CHECKIN, "check-in index");
        check(DockPages.indexOfPlugin(MemoryPlugin.ID) == DockPages.MEMORY, "memory index");
        check(DockPages.indexOfPlugin(QuizPlugin.ID) == DockPages.QUIZ, "quiz index");
        check(DockPages.indexOfPlugin(FreezePlugin.ID) == DockPages.FREEZE, "freeze index");
        check(DockPages.activity(DockPages.CHECKIN) == CheckinActivity.class, "check-in activity");
        check(DockPages.activity(DockPages.MEMORY) == MemoryActivity.class, "memory activity");
        check(DockPages.activity(DockPages.QUIZ) == QuizActivity.class, "quiz activity");

        check(DockPages.isPlugin(DockPages.CHECKIN), "check-in is a plugin page");
        check(DockPages.isPlugin(DockPages.MEMORY), "memory is a plugin page");
        check(DockPages.isPlugin(DockPages.QUIZ), "quiz is a plugin page");
        check(DockPages.isPlugin(DockPages.FREEZE), "freeze is an independently disableable plugin");
        check(!DockPages.isPlugin(DockPages.GUARD), "guard remains host core");
        check(java.util.Arrays.equals(DockPages.navigationOrder(true, true, true, true),
                DockPages.NAV_ORDER), "all plugins restore the full dock");
        check(java.util.Arrays.equals(DockPages.navigationOrder(false, false, false, false),
                new int[]{DockPages.GUARD}),
                "guard remains when all dock plugins are disabled");
        check(java.util.Arrays.equals(DockPages.navigationOrder(true, false, true, false),
                new int[]{DockPages.CHECKIN, DockPages.GUARD, DockPages.QUIZ}),
                "disabled plugin is omitted without reordering stable pages");
        check(java.util.Arrays.equals(DockPages.navigationOrder(true, true, true, false),
                new int[]{DockPages.CHECKIN, DockPages.MEMORY, DockPages.GUARD, DockPages.QUIZ}),
                "freeze can be hidden while check-in tools remain");
        check(java.util.Arrays.equals(TabOrder.ORDER, DockPages.NAV_ORDER), "single navigation order");
        System.out.println("App plugins: " + checks + " checks passed");
    }
}
