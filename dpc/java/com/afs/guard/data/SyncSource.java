package com.afs.guard.data;

import java.util.List;

public interface SyncSource {
    List<DataRecords.OutboxRecord> getPendingOperations();
    void markCompleted(String operationId);
    void markFailed(String operationId, String reason);
}

