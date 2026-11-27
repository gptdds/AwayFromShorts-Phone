package com.afs.guard;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Versioned capability declaration shared with the PC client. This is data,
 * not a second business model: Hub still preserves pc/phone section ownership.
 */
final class CrossContract {
    static final String ID = "awayfromshorts.cross-platform";
    static final int VERSION = 1;
    static final String FILE = "contracts/awayfromshorts-cross-platform-v1.json";

    private CrossContract() {}

    static JSONObject info(Context context) throws Exception {
        return info(context, appVersion(context));
    }

    static JSONObject info(Context context, String appVersion) throws Exception {
        InputStream input = context.getAssets().open(FILE);
        try {
            return info(input, appVersion);
        } finally {
            input.close();
        }
    }

    static JSONObject info(InputStream input, String appVersion) throws Exception {
        byte[] raw = readAll(input);
        JSONObject contract = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        validate(contract);

        JSONObject scope = contract.getJSONObject("scopeMask");
        JSONObject versions = new JSONObject();
        JSONArray supported = new JSONArray();
        JSONArray features = contract.getJSONArray("features");
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            if (feature.getJSONObject("support").getBoolean("phone")) {
                String id = feature.getString("id");
                versions.put(id, feature.getInt("version"));
                supported.put(id);
            }
        }

        return new JSONObject()
                .put("platform", "phone")
                .put("appVersion", appVersion)
                .put("contractVersion", contract.getInt("contractVersion"))
                .put("contractHash", sha256(raw))
                .put("scopeMask", new JSONObject()
                        .put("schedule", scope.getInt("schedule"))
                        .put("quota", scope.getInt("quota"))
                        .put("orSet", scope.getInt("orSet"))
                        .put("study", scope.getInt("study"))
                        .put("penalty", scope.getInt("penalty"))
                        .put("all", scope.getInt("all")))
                .put("featureVersions", versions)
                .put("supportedFeatures", supported);
    }

    static void validate(JSONObject contract) throws Exception {
        if (!ID.equals(contract.getString("id"))) {
            throw new IllegalArgumentException("contract id mismatch");
        }
        if (contract.getInt("contractVersion") != VERSION
                || contract.getInt("minimumReaderVersion") > VERSION) {
            throw new IllegalArgumentException("contract version incompatible");
        }
        String unknownFields = contract.getString("unknownFieldPolicy");
        if (!"reject".equals(unknownFields) && !"preserve".equals(unknownFields)) {
            throw new IllegalArgumentException("unknownFieldPolicy invalid");
        }
        String unknownFeatures = contract.getString("unknownFeaturePolicy");
        if (!"reject".equals(unknownFeatures) && !"warn".equals(unknownFeatures)) {
            throw new IllegalArgumentException("unknownFeaturePolicy invalid");
        }

        JSONObject scope = contract.getJSONObject("scopeMask");
        requireScope(scope, "schedule", 1);
        requireScope(scope, "quota", 2);
        requireScope(scope, "orSet", 4);
        requireScope(scope, "study", 8);
        requireScope(scope, "penalty", 16);
        requireScope(scope, "all", 31);
        int combined = scope.getInt("schedule") | scope.getInt("quota")
                | scope.getInt("orSet") | scope.getInt("study")
                | scope.getInt("penalty");
        if (combined != 31) {
            throw new IllegalArgumentException("scopeMask invalid");
        }

        Set<String> seen = new HashSet<>();
        JSONArray features = contract.getJSONArray("features");
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            String id = feature.getString("id");
            if (!id.matches("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+$")) {
                throw new IllegalArgumentException("feature id invalid: " + id);
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("duplicate feature: " + id);
            }
            if (feature.getInt("version") < 1) {
                throw new IllegalArgumentException("feature version invalid: " + id);
            }
            JSONObject support = feature.getJSONObject("support");
            if (!(support.get("pc") instanceof Boolean)
                    || !(support.get("phone") instanceof Boolean)) {
                throw new IllegalArgumentException("feature support invalid: " + id);
            }
            JSONArray requires = feature.getJSONArray("requires");
            if (requires.length() == 0) {
                throw new IllegalArgumentException("feature requires empty: " + id);
            }
            Set<String> required = new HashSet<>();
            for (int j = 0; j < requires.length(); j++) {
                String name = requires.getString(j).trim();
                if (name.isEmpty() || !required.add(name)) {
                    throw new IllegalArgumentException("feature requires invalid: " + id);
                }
            }
        }

        JSONObject bindings = contract.getJSONObject("platformBindings");
        if (bindings.optJSONObject("pc") == null || bindings.optJSONObject("phone") == null) {
            throw new IllegalArgumentException("platformBindings invalid");
        }
        JSONObject sync = contract.getJSONObject("sync");
        if (!"hub-config.json".equals(sync.getString("hub"))
                || !"contract".equals(sync.getString("capabilityField"))
                || !"preserve".equals(sync.getString("unknownFields"))
                || !"warn-and-skip".equals(sync.getString("unsupportedFeature"))) {
            throw new IllegalArgumentException("sync contract invalid");
        }
        JSONArray sections = sync.getJSONArray("deviceSections");
        Set<String> sectionSet = new HashSet<>();
        for (int i = 0; i < sections.length(); i++) sectionSet.add(sections.getString(i));
        if (!sectionSet.contains("pc") || !sectionSet.contains("phone")) {
            throw new IllegalArgumentException("deviceSections invalid");
        }
    }

    private static void requireScope(JSONObject scope, String name, int expected)
            throws Exception {
        if (scope.getInt(name) != expected) {
            throw new IllegalArgumentException("scopeMask invalid: " + name);
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            String version = info.versionName;
            return version == null || version.isEmpty() ? BuildConfig.VERSION_NAME : version;
        } catch (Throwable ignored) {
            return BuildConfig.VERSION_NAME;
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }
}
