package com.afs.guard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CrossContractDeviceTest extends Instrumentation {
    private int checks;
    private boolean publishRequested;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        publishRequested = args != null
                && (args.getBoolean("publishContract", false)
                || "1".equals(args.getString("publishContract")));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            JSONObject info = CrossContract.info(target);
            PackageInfo pkg = target.getPackageManager()
                    .getPackageInfo(target.getPackageName(), 0);
            check("phone".equals(info.getString("platform")), "phone platform");
            check(info.getInt("contractVersion") == 1, "contract version");
            check(info.getString("contractHash")
                    .equals("3f864681f65395760fa1da579bce0697b302cc7a4f29dad26b5bb1572cad6fd8"),
                    "installed asset hash");
            check(pkg.versionName.equals(info.getString("appVersion")),
                    "installed app version");
            check(info.getJSONObject("scopeMask").getInt("all") == 31,
                    "all scope mask");
            JSONArray supported = info.getJSONArray("supportedFeatures");
            check(contains(supported, "quota.or-set"), "OR-set capability");
            check(contains(supported, "freeze.weekday-pause"), "weekday pause capability");
            check(contains(supported, "memo.markdown"), "memo markdown capability");
            if (publishRequested) {
                check(Hub.pushNowSync(target), "hub contract published");
            }
            result.putString("stream", "Cross contract device: " + checks + " checks passed\n");
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream",
                    "FAILED " + android.util.Log.getStackTraceString(e));
            finish(1, result);
        }
    }

    private boolean contains(JSONArray array, String value) throws Exception {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.getString(i))) return true;
        }
        return false;
    }

    private void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
}
