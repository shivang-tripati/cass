package com.shivang.obd.campaign;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;

/**
 * An execution referenced its configuration snapshot but the snapshot row
 * could not be resolved within the execution's tenant (VB-6A correction).
 * <p>
 * Under normal operation this is impossible: the snapshot is created in
 * the same transaction as the execution and the database FK
 * ({@code campaign_executions.configuration_snapshot_id NOT NULL}) makes
 * the association mandatory. Thrown as a data-integrity error — there is
 * deliberately no fallback to the live campaign configuration, because a
 * running execution must never silently adopt configuration it was not
 * created with.
 */
public class ExecutionConfigurationMissingException extends BusinessException {

    public ExecutionConfigurationMissingException() {
        super(CommonErrorCode.INTERNAL_SERVER_ERROR,
                "Execution configuration snapshot is missing (data integrity violation)");
    }
}
