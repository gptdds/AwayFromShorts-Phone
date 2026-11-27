package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class CrossContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File file = new File("assets", CrossContract.FILE);
        byte[] raw = Files.readAllBytes(file.toPath());
        JSONObject contract = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        CrossContract.validate(contract);
        check(contract.getString("id").equals(CrossContract.ID), "contract id");
        check(contract.getInt("contractVersion") == 1, "contract version");
        check(contract.getJSONObject("scopeMask").getInt("all") == 31,
                "scope mask all values");
        check(contract.getString("unknownFieldPolicy").equals("preserve"),
                "unknown fields preserved");
        check(contract.getString("unknownFeaturePolicy").equals("warn"),
                "unknown features warn");
        check(contract.getJSONObject("sync").getString("unsupportedFeature")
                .equals("warn-and-skip"), "unsupported features skip safely");

        JSONObject info;
        FileInputStream input = new FileInputStream(file);
        try {
            info = CrossContract.info(input, "test");
        } finally {
            input.close();
        }
        check(info.getString("platform").equals("phone"), "phone capability platform");
        check(info.getString("contractHash")
                .equals("3f864681f65395760fa1da579bce0697b302cc7a4f29dad26b5bb1572cad6fd8"),
                "asset hash");
        JSONArray supported = info.getJSONArray("supportedFeatures");
        check(contains(supported, "freeze.weekday-pause"), "weekday pause supported");
        check(contains(supported, "quota.or-set"), "OR-set supported");
        check(contains(supported, "memo.markdown"), "memo markdown supported");
        check(contains(supported, "freeze.schedule"), "freeze schedule supported");

        JSONObject duplicate = new JSONObject(contract.toString());
        duplicate.getJSONArray("features").getJSONObject(0)
                .put("requires", new JSONArray().put("timezone").put("timezone"));
        rejects(duplicate, "duplicate requires rejected");

        JSONObject empty = new JSONObject(contract.toString());
        empty.getJSONArray("features").getJSONObject(0).put("requires", new JSONArray());
        rejects(empty, "empty requires rejected");

        JSONObject badScope = new JSONObject(contract.toString());
        badScope.getJSONObject("scopeMask").put("all", 30);
        rejects(badScope, "bad scope rejected");

        JSONObject future = new JSONObject(contract.toString()).put("contractVersion", 2);
        rejects(future, "future contract rejected");

        System.out.println("Cross contract: " + checks + " checks passed");
    }

    private static boolean contains(JSONArray array, String value) throws Exception {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.getString(i))) return true;
        }
        return false;
    }

    private static void rejects(JSONObject contract, String label) {
        try {
            CrossContract.validate(contract);
            throw new AssertionError(label);
        } catch (IllegalArgumentException expected) {
            checks++;
        } catch (Exception e) {
            throw new AssertionError(label, e);
        }
    }

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
}
