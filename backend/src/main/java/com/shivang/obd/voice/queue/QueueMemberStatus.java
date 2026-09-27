package com.shivang.obd.voice.queue;

/**
 * Membership lifecycle (VB-4B). Independent of the agent's own
 * administrative status and presence: an ACTIVE agent with an INACTIVE
 * membership is not part of the queue for routing purposes.
 */
public enum QueueMemberStatus {
    /** The agent belongs to the queue. */
    ACTIVE,
    /** The membership is retained but the agent is excluded from the queue. */
    INACTIVE
}
