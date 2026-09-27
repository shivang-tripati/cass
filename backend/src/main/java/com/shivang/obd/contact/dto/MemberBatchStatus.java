package com.shivang.obd.contact.dto;

/**
 * Per-item outcome of a batch member add/remove operation. The batch is
 * never fail-fast: every requested contact receives exactly one result.
 */
public enum MemberBatchStatus {

    /** The membership was newly created (batch add) or removed (batch remove). */
    CREATED,

    /** The membership already existed (add) — no second row was made. */
    EXISTS,

    /** The membership did not exist and nothing was removed (batch remove). */
    NOT_FOUND,

    /** The contact is not a live identity of the group's tenant (add). */
    NOT_FOUND_CONTACT,

    /** The item could not be processed (e.g. a concurrency loss); retry the item. */
    ERROR
}
