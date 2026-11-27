package com.afs.guard.data;

import java.util.List;

public interface FocusRepository {
    DataRecords.FocusSessionRecord start(String sessionType, long plannedEndAt);
    DataRecords.FocusSessionRecord active();
    void finish(String id, long endedAt, String reason, String status);
    List<DataRecords.FocusSessionRecord> between(long from, long to);
}

