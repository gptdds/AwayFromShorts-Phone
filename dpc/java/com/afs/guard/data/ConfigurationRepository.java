package com.afs.guard.data;

public interface ConfigurationRepository {
    String get(String key, String fallback);
    int getInt(String key, int fallback);
    boolean getBoolean(String key, boolean fallback);
    void put(String key, String value, String type);
    void putInt(String key, int value);
    void putBoolean(String key, boolean value);
}

