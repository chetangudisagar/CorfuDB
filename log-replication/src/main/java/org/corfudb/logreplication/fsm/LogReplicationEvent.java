package org.corfudb.logreplication.fsm;

import lombok.Data;

import java.util.UUID;

/**
 * A class that represents a Log Replication Event.
 */
@Data
public class LogReplicationEvent {

    public LogReplicationEvent(LogReplicationEventType type) {
        this.eventID = UUID.randomUUID();
        this.type = type;
    }

    /**
     * Enum listing the various type of LogReplicationEvent.
     */
    public enum LogReplicationEventType {
        SNAPSHOT_SYNC_REQUEST,      // External event which signals start of a snapshot sync (full-sync)
        TRIMMED_EXCEPTION,          // Internal event indicating that log has been trimmed on access
        SNAPSHOT_SYNC_CANCEL,       // External event requesting to cancel snapshot sync (full-sync)
        REPLICATION_START,          // External event which signals start of log replication process
        REPLICATION_STOP,           // External event which signals stop of log replication process
        SNAPSHOT_SYNC_COMPLETE      // Internal event which signals a snapshot sync has been completed
        // REPLICATION_SHUTDOWN?
    }

    /**
     *  Log Replication Event Identifier
     */
    private UUID eventID;

    /**
     *  Log Replication Event Type
     */
    private LogReplicationEventType type;

}