package com.shivang.obd.campaign;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VB-8D test support: a {@link TransactionTemplate} that executes its callback
 * without a real database transaction.
 *
 * <p>VB-8D moved the dial step's transaction boundary off an annotation and onto
 * an explicit {@code TransactionTemplate} - once per attempt, so a batch-wide
 * rollback can no longer erase calls already handed to FreeSWITCH. Pure-unit dial
 * tests construct {@code OutboundDialService} by hand and have no Spring context
 * and therefore no transaction manager, so they need one that simply runs the
 * work.
 *
 * <p>This is <b>not</b> a way to fake transaction semantics in the tests that
 * actually assert them. Anything that needs real commit/rollback behaviour uses
 * the real {@code transactionTemplate} against PostgreSQL - see
 * {@code SchedulerTransactionBoundaryPostgresIntegrationTest}, which is where the
 * transaction boundaries are proved.
 */
final class TransactionTestSupport {

    private TransactionTestSupport() {
    }

    /** A template that runs the callback inline and pretends nothing. */
    static TransactionTemplate direct() {
        return new TransactionTemplate(new PassThroughTransactionManager());
    }

    /**
     * A manager whose only behaviour is to let the work run. Commit and rollback
     * are no-ops, which is exactly right for a unit test that is not asserting
     * transactional behaviour.
     */
    private static final class PassThroughTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no real transaction exists
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // nothing to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }
}
