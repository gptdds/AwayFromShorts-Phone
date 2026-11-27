package com.afs.guard.data;

public interface SyncOutbox extends SyncSource {
    String enqueue(String entityType, String entityId, String operation,
                   int entityRevision, String payload);
}

