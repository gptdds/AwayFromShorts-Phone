package com.afs.guard.data;

import java.util.List;

public interface ApplicationRepository {
    List<DataRecords.AppRecord> all();
    DataRecords.AppRecord find(String packageName);
    DataRecords.AppRecord upsert(String packageName, String displayName, boolean installed);
    void setEnabled(String packageName, boolean enabled);
    void setEnforcementState(String packageName, boolean suspended, long changedAt);
}

