package com.afs.guard.data;

import com.afs.guard.Rule;

public final class DataRecords {
    private DataRecords() { }

    public static final class AppRecord {
        public final String id;
        public final String packageName;
        public final String displayName;
        public final String category;
        public final boolean enabled;
        public final boolean installed;
        public final boolean suspended;
        public final long lastEnforcedAt;
        public final long createdAt;
        public final long updatedAt;

        public AppRecord(String id, String packageName, String displayName, String category,
                         boolean enabled, boolean installed, boolean suspended, long lastEnforcedAt,
                         long createdAt, long updatedAt) {
            this.id = id;
            this.packageName = packageName;
            this.displayName = displayName;
            this.category = category;
            this.enabled = enabled;
            this.installed = installed;
            this.suspended = suspended;
            this.lastEnforcedAt = lastEnforcedAt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static final class RuleRecord {
        public final String id;
        public final String applicationId;
        public final String packageName;
        public final Rule rule;
        public final int revision;
        public final boolean enabled;
        public final int priority;
        public final long addedAt;
        public final long createdAt;
        public final long updatedAt;

        public RuleRecord(String id, String applicationId, String packageName, Rule rule,
                          int revision, boolean enabled, int priority, long addedAt,
                          long createdAt, long updatedAt) {
            this.id = id;
            this.applicationId = applicationId;
            this.packageName = packageName;
            this.rule = rule;
            this.revision = revision;
            this.enabled = enabled;
            this.priority = priority;
            this.addedAt = addedAt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static final class EventRecord {
        public final String id;
        public final String eventType;
        public final String applicationId;
        public final long eventTime;
        public final String localDate;
        public final String timezone;
        public final String executionResult;
        public final String enforcementMethod;
        public final String errorCode;
        public final String metadata;
        public final String dedupeKey;

        public EventRecord(String eventType, String applicationId, long eventTime,
                           String localDate, String timezone, String executionResult,
                           String enforcementMethod, String errorCode, String metadata,
                           String dedupeKey) {
            this.id = java.util.UUID.randomUUID().toString();
            this.eventType = eventType;
            this.applicationId = applicationId;
            this.eventTime = eventTime;
            this.localDate = localDate;
            this.timezone = timezone;
            this.executionResult = executionResult;
            this.enforcementMethod = enforcementMethod;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.metadata = metadata == null ? "{}" : metadata;
            this.dedupeKey = dedupeKey;
        }
    }

    public static final class FreezeSlotRecord {
        public final String id;
        public final Rule rule;
        public final String scheduleKey;

        public FreezeSlotRecord(String id, Rule rule, String scheduleKey) {
            this.id = id;
            this.rule = rule;
            this.scheduleKey = scheduleKey;
        }
    }

    public static final class FocusSessionRecord {
        public final String id;
        public final String sessionType;
        public final long startedAt;
        public final long plannedEndAt;
        public final long endedAt;
        public final String endReason;
        public final String status;
        public final long createdAt;

        public FocusSessionRecord(String id, String sessionType, long startedAt, long plannedEndAt,
                                  long endedAt, String endReason, String status, long createdAt) {
            this.id = id;
            this.sessionType = sessionType;
            this.startedAt = startedAt;
            this.plannedEndAt = plannedEndAt;
            this.endedAt = endedAt;
            this.endReason = endReason;
            this.status = status;
            this.createdAt = createdAt;
        }
    }

    public static final class DailyStatRecord {
        public final String localDate;
        public final String timezone;
        public final long focusDuration;
        public final long enforcedDuration;
        public final int blockedAttempts;
        public final int successfulEnforcements;
        public final int failedEnforcements;
        public final long updatedAt;

        public DailyStatRecord(String localDate, String timezone, long focusDuration,
                               long enforcedDuration, int blockedAttempts,
                               int successfulEnforcements, int failedEnforcements,
                               long updatedAt) {
            this.localDate = localDate;
            this.timezone = timezone;
            this.focusDuration = focusDuration;
            this.enforcedDuration = enforcedDuration;
            this.blockedAttempts = blockedAttempts;
            this.successfulEnforcements = successfulEnforcements;
            this.failedEnforcements = failedEnforcements;
            this.updatedAt = updatedAt;
        }
    }

    public static final class OutboxRecord {
        public final String id;
        public final String entityType;
        public final String entityId;
        public final String operation;
        public final int entityRevision;
        public final int payloadVersion;
        public final String payload;
        public final long createdAt;
        public final int retryCount;
        public final String syncStatus;
        public final String lastError;
        public final long nextAttemptAt;

        public OutboxRecord(String id, String entityType, String entityId, String operation,
                            int entityRevision, int payloadVersion, String payload,
                            long createdAt, int retryCount, String syncStatus,
                            String lastError, long nextAttemptAt) {
            this.id = id;
            this.entityType = entityType;
            this.entityId = entityId;
            this.operation = operation;
            this.entityRevision = entityRevision;
            this.payloadVersion = payloadVersion;
            this.payload = payload;
            this.createdAt = createdAt;
            this.retryCount = retryCount;
            this.syncStatus = syncStatus;
            this.lastError = lastError;
            this.nextAttemptAt = nextAttemptAt;
        }
    }
}

