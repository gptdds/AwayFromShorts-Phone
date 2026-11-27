package com.afs.guard;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/** 热爱券照片 30 天清理的离线回归（不依赖 Android Context）。 */
final class LovePhotoPruneTest {

    private static int checks;
    private static final long DAY = 24L * 3600_000L;

    private static void ok(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want " + want + " got " + got);
        }
    }

    private static File write(File dir, String name, long modified) throws Exception {
        File file = new File(dir, name);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[]{1, 2, 3});
        } finally {
            out.close();
        }
        ok(file.setLastModified(modified), "setLastModified " + name);
        return file;
    }

    public static void main(String[] args) throws Exception {
        long now = 1_800_000_000_000L;

        /* 1. 边界：严格超过 30 天才过期 */
        ok(LovePhotoPrune.expired(now - 31 * DAY, now), "31 天前过期");
        ok(LovePhotoPrune.expired(now - 30 * DAY - 1, now), "刚过 30 天过期");
        ok(!LovePhotoPrune.expired(now - 30 * DAY, now), "正好 30 天保留");
        ok(!LovePhotoPrune.expired(now - 29 * DAY, now), "29 天保留");
        ok(!LovePhotoPrune.expired(now + DAY, now), "未来时间保留");
        ok(!LovePhotoPrune.expired(0L, now), "缺失时间戳不动");

        /* 2. 目录清理：只删过期普通文件 */
        File dir = Files.createTempDirectory("afs-love-photos").toFile();
        File old = write(dir, "old.jpg", now - 40 * DAY);
        File fresh = write(dir, "fresh.jpg", now - 10 * DAY);
        File edge = write(dir, "edge.jpg", now - 30 * DAY);
        File sub = new File(dir, "nested");
        ok(sub.mkdir(), "创建子目录");

        eq(1, LovePhotoPrune.prune(dir, now), "只删一张过期照片");
        ok(!old.exists(), "40 天前的照片已删除");
        ok(fresh.exists(), "10 天前的照片保留");
        ok(edge.exists(), "正好 30 天的照片保留");
        ok(sub.isDirectory(), "子目录不删");

        /* 3. 目录不存在/为空时不报错 */
        eq(0, LovePhotoPrune.prune(new File(dir, "missing"), now), "目录不存在返回 0");
        eq(0, LovePhotoPrune.prune(null, now), "null 目录返回 0");

        /* 4. 再跑一次不应重复删 */
        eq(0, LovePhotoPrune.prune(dir, now), "清理幂等");

        for (File f : new File[]{fresh, edge}) {
            f.delete();
        }
        sub.delete();
        dir.delete();

        System.out.println("Love photo retention: " + checks + " checks passed");
    }
}
