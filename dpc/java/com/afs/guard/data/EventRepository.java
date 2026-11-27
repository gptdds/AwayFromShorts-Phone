package com.afs.guard.data;

import java.util.List;

public interface EventRepository {
    void record(DataRecords.EventRecord event);
    List<DataRecords.EventRecord> recent(int limit);
    int deleteOlderThan(long cutoff);
}

