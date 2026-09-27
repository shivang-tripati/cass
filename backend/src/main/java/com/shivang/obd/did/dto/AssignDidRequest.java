package com.shivang.obd.did.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * DID assignment request (VB-5C). The target organization is chosen by
 * the caller; the caller's authority to target it is derived from the
 * server-side organizational context, never from this body alone.
 */
public record AssignDidRequest(
    @NotNull UUID targetId
) {
}
