package com.afs.guard.data;

import java.util.List;

public interface StatisticsRepository {
    DataRecords.DailyStatRecord forDay(String localDate, String timezone);
    void upsert(DataRecords.DailyStatRecord record);
    List<DataRecords.DailyStatRecord> range(String fromDate, String toDate, String timezone);
    void increment(String localDate, String timezone, long focusDuration,
                   long enforcedDuration, int blockedAttempts,
                   int successfulEnforcements, int failedEnforcements, long updatedAt);
}

