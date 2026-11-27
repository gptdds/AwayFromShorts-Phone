package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * 热爱券照片只保留最近 30 天。
 *
 * <p>照片存在应用私有目录 {@code files/love-photos/}，不会进相册。券记录里保留
 * 路径和 {@code photoAt}，方便对账；文件本身过 30 天由这里清掉，避免越攒越多。</p>
 */
final class LovePhotoPrune {

    static final long KEEP_MS = 30L * 24 * 3600_000L;
    private static final String SP = "afs_love_photos";
    private static final String K_LAST = "prune_at";
    /** 启动/心跳都会调，12 小时最多真正扫一次目录。 */
    private static final long MIN_GAP_MS = 12L * 3600_000L;

    private LovePhotoPrune() {
    }

    /** 纯判定：修改时间早于 30 天前算过期；时间戳缺失（<=0）不动。 */
    static boolean expired(long modified, long now) {
        return modified > 0L && now - modified > KEEP_MS;
    }

    /** 删除目录里过期的普通文件（不递归、不动子目录），返回删除数量。 */
    static int prune(File dir, long now) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            if (file == null || file.isDirectory()) {
                continue;
            }
            if (expired(file.lastModified(), now) && file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /** 进打卡页/守卫心跳时顺手清理；节流后只是一次 SharedPreferences 读取。 */
    static void pruneAsync(Context c) {
        if (c == null) {
            return;
        }
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - sp.getLong(K_LAST, 0L) < MIN_GAP_MS) {
            return;
        }
        sp.edit().putLong(K_LAST, now).apply();
        new Thread(new Runnable() {
            public void run() {
                try {
                    int n = prune(LovePhotoProvider.dir(app), System.currentTimeMillis());
                    if (n > 0) {
                        Log.i(Guard.TAG, "love photos pruned: " + n);
                    }
                } catch (RuntimeException error) {
                    Log.w(Guard.TAG, "love photo prune failed: "
                            + error.getClass().getSimpleName());
                }
            }
        }, "afs-love-photo-prune").start();
    }
}
