package com.indore.pathome.spaces.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Transaction-local PostgreSQL command bounds for governed Operations mutations.
 *
 * <p>{@code set_config(name, value, is_local => true)} is issued through the {@link EntityManager}
 * that owns the current transaction, so the bounds apply to the very connection and transaction that
 * subsequently acquire the governance advisory lock, the City row lock and the actor profile/grant
 * row locks. PostgreSQL reverts transaction-local settings automatically at commit or rollback, so a
 * pooled connection cannot carry the bound into a later transaction, and neither the session, the
 * pool nor any global PostgreSQL or datasource timeout configuration is touched.
 *
 * <p>The bounds are deliberately command-scoped rather than global: an unbounded wait on a
 * governance guard would pin an application thread (and a pooled connection) indefinitely, whereas a
 * bounded wait fails fast, rolls the whole command back, and lets the caller retry from authoritative
 * database state.
 */
@Component
public class TransactionalCommandBounds {
    /** Advisory, City and actor/grant lock acquisition must fail fast rather than wait forever. */
    static final String LOCK_TIMEOUT = "5s";
    /** Hard ceiling for the whole governed command, including its audit write. */
    static final String STATEMENT_TIMEOUT = "10s";

    private final EntityManager entityManager;

    public TransactionalCommandBounds(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Applies both bounds to the current transaction, before any blocking guard is acquired. */
    public void apply() {
        setLocal("lock_timeout", LOCK_TIMEOUT);
        setLocal("statement_timeout", STATEMENT_TIMEOUT);
    }

    private void setLocal(String parameter, String value) {
        // The single-column result is intentionally discarded; the side effect is the transaction-local
        // setting. Executing this on the EntityManager is what guarantees the same connection as the locks.
        entityManager.createNativeQuery("select set_config(:parameter, :value, true)")
                .setParameter("parameter", parameter)
                .setParameter("value", value)
                .getSingleResult();
    }
}
