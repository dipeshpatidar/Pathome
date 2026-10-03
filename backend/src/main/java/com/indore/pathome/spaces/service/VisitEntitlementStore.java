package com.indore.pathome.spaces.service;

import org.springframework.jdbc.core.JdbcTemplate;
import com.indore.pathome.spaces.config.VisitExecutionProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/** Transactional ledger writer and locked account read model for visit credits. */
@Service
public class VisitEntitlementStore {
    private final JdbcTemplate jdbc;
    private final VisitExecutionProperties properties;

    public VisitEntitlementStore(JdbcTemplate jdbc, VisitExecutionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public boolean hasReservation(Long sessionId) { return ledgerExists("RESERVE:" + sessionId) && !ledgerExists("RELEASE:" + sessionId); }

    @Transactional
    public boolean reserve(Long userId, Long sessionId, Instant horizon) {
        return reserve(userId, sessionId, horizon, false);
    }

    /** Allows a credit hold after the booked interval only when no-show evidence is being finalized. */
    @Transactional
    public boolean reserveForNoShow(Long userId, Long sessionId, Instant horizon) {
        return reserve(userId, sessionId, horizon, true);
    }

    private boolean reserve(Long userId, Long sessionId, Instant horizon, boolean allowExpiredInterval) {
        if (jdbc.queryForObject("select count(*) from visit_sessions where id=? and tenant_id=? and status='SCHEDULED' and scheduled_at <= ?",
                Integer.class, sessionId, userId, Timestamp.from(horizon)) == 0) return false;
        if (!allowExpiredInterval && jdbc.queryForObject(
                "select count(*) from visit_sessions where id=? and tenant_id=? and status='SCHEDULED' and reserved_end_at > current_timestamp",
                Integer.class, sessionId, userId) == 0) return false;
        long accountId = lockOrCreateAccount(userId);
        String key = "RESERVE:" + sessionId;
        if (ledgerExists(key)) return !ledgerExists("RELEASE:" + sessionId);
        Integer available = jdbc.queryForObject("select available_credits from tenant_visit_entitlement_accounts where id=?", Integer.class, accountId);
        if (available == null || available <= 0) return false;
        requireChanged(jdbc.update("update tenant_visit_entitlement_accounts set available_credits=available_credits-1, reserved_credits=reserved_credits+1, version=version+1, updated_at=current_timestamp where id=? and available_credits>0", accountId));
        jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,session_id,event_type,available_delta,reserved_delta,reason_code,idempotency_key) values (?,?,?,'RESERVE',-1,1,'SCHEDULE_CONFIRMED',?)",
                accountId, userId, sessionId, key);
        mirrorLegacyBalance(userId, accountId);
        return true;
    }

    @Transactional
    public void release(Long userId, Long sessionId, Long actorId, String reason) {
        long accountId = lockOrCreateAccount(userId);
        String key = "RELEASE:" + sessionId;
        if (ledgerExists(key) || ledgerExists("CONSUME:" + sessionId) || !ledgerExists("RESERVE:" + sessionId)) return;
        requireChanged(jdbc.update("update tenant_visit_entitlement_accounts set available_credits=available_credits+1, reserved_credits=reserved_credits-1, version=version+1, updated_at=current_timestamp where id=? and reserved_credits>0", accountId));
        jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,session_id,event_type,available_delta,reserved_delta,actor_user_id,reason_code,idempotency_key) values (?,?,?,'RELEASE',1,-1,?,?,?)",
                accountId, userId, sessionId, actorId, reason, key);
        mirrorLegacyBalance(userId, accountId);
    }

    @Transactional
    public void consume(Long userId, Long sessionId, Long actorId) {
        long accountId = lockOrCreateAccount(userId);
        String key = "CONSUME:" + sessionId;
        if (ledgerExists(key)) return;
        if (!ledgerExists("RESERVE:" + sessionId) || ledgerExists("RELEASE:" + sessionId))
            throw new IllegalStateException("A reserved visit entitlement is required to start this session");
        requireChanged(jdbc.update("update tenant_visit_entitlement_accounts set reserved_credits=reserved_credits-1, version=version+1, updated_at=current_timestamp where id=? and reserved_credits>0", accountId));
        jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,session_id,event_type,available_delta,reserved_delta,actor_user_id,reason_code,idempotency_key) values (?,?,?,'CONSUME',0,-1,?,'OTP_START',?)",
                accountId, userId, sessionId, actorId, key);
    }

    @Transactional
    public void forfeitNoShow(Long userId, Long sessionId) {
        long accountId = lockOrCreateAccount(userId);
        String key = "FORFEIT_NO_SHOW:" + sessionId;
        if (ledgerExists(key) || ledgerExists("CONSUME:" + sessionId)
                || ledgerExists("RELEASE:" + sessionId) || !ledgerExists("RESERVE:" + sessionId)) return;
        requireChanged(jdbc.update("update tenant_visit_entitlement_accounts set reserved_credits=reserved_credits-1,version=version+1,updated_at=current_timestamp where id=? and reserved_credits>0",
                accountId));
        jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,session_id,event_type,available_delta,reserved_delta,reason_code,idempotency_key) values (?,?,?,'FORFEIT_NO_SHOW',0,-1,'UNCONTESTED_NO_SHOW',?)",
                accountId, userId, sessionId, key);
    }

    @Transactional
    public void operationalRestore(Long userId, Long sessionId, Long actorId, String reason, String idempotencyKey) {
        long accountId = lockOrCreateAccount(userId);
        if (!ledgerExists("CONSUME:" + sessionId) && !ledgerExists("FORFEIT_NO_SHOW:" + sessionId))
            throw new IllegalStateException("A consumed visit credit or finalized no-show forfeiture is required for restoration");
        if (ledgerExists(idempotencyKey)) return;
        Integer restoredToday = jdbc.queryForObject("select count(*) from visit_entitlement_ledger where user_id=? and event_type='OE_OPERATIONAL_RESTORE' and occurred_at >= current_timestamp - interval '24 hours'",
                Integer.class, userId);
        if (restoredToday != null && restoredToday >= properties.getMaximumOperationalRestoresPerDay())
            throw new IllegalStateException("The operational restore limit has been reached for the last 24 hours");
        requireChanged(jdbc.update("update tenant_visit_entitlement_accounts set available_credits=available_credits+1,version=version+1,updated_at=current_timestamp where id=?",
                accountId));
        jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,session_id,event_type,available_delta,reserved_delta,actor_user_id,reason_code,idempotency_key) values (?,?,?,'OE_OPERATIONAL_RESTORE',1,0,?,?,?)",
                accountId, userId, sessionId, actorId, reason, idempotencyKey);
        mirrorLegacyBalance(userId, accountId);
    }

    private long lockOrCreateAccount(Long userId) {
        jdbc.update("insert into tenant_visit_entitlement_accounts(user_id,available_credits) select id,greatest(coalesce(free_visits_remaining,0),0) from users where id=? on conflict(user_id) do nothing", userId);
        Integer initial = jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?", Integer.class, "INITIAL_GRANT:" + userId);
        if (initial != null && initial == 0) {
            Integer available = jdbc.queryForObject("select available_credits from tenant_visit_entitlement_accounts where user_id=?", Integer.class, userId);
            if (available != null && available > 0) jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,event_type,available_delta,reserved_delta,reason_code,idempotency_key) select id,user_id,'INITIAL_GRANT',available_credits,0,'LAZY_ACCOUNT_INITIALIZATION',? from tenant_visit_entitlement_accounts where user_id=? on conflict(idempotency_key) do nothing", "INITIAL_GRANT:" + userId, userId);
        }
        return jdbc.queryForObject("select id from tenant_visit_entitlement_accounts where user_id=? for update", Long.class, userId);
    }

    private boolean ledgerExists(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from visit_entitlement_ledger where idempotency_key=?)", Boolean.class, key));
    }

    private void mirrorLegacyBalance(Long userId, long accountId) {
        jdbc.update("update users set free_visits_remaining=(select available_credits from tenant_visit_entitlement_accounts where id=?) where id=?", accountId, userId);
    }

    private void requireChanged(int rows) {
        if (rows != 1) throw new IllegalStateException("Visit entitlement account changed; retry the operation");
    }
}
