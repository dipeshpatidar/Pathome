package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitEntitlementView;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitPolicy;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitPolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TenantVisitEntitlementService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final VisitPolicyRepository policies;

    public TenantVisitEntitlementService(JdbcTemplate jdbc, UserRepository users, VisitPolicyRepository policies) {
        this.jdbc = jdbc;
        this.users = users;
        this.policies = policies;
    }

    /** The user, account, and grant ledger event commit together. */
    @Transactional
    public User createTenantWithGrant(User user) {
        VisitPolicy policy = policies.findById(VisitPolicy.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Visit policy is unavailable"));
        int granted = policy.getFreeVisitSessionsDefault();
        if (granted < 0) throw new IllegalStateException("Visit policy grant cannot be negative");
        user.setFreeVisitsRemaining(granted);
        User saved = users.saveAndFlush(user);
        Long accountId = jdbc.queryForObject(
                "insert into tenant_visit_entitlement_accounts(user_id,available_credits,total_granted_credits) values (?,?,?) returning id",
                Long.class, saved.getId(), granted, granted);
        if (accountId == null) throw new IllegalStateException("Visit entitlement account was not created");
        if (granted > 0) {
            jdbc.update("insert into visit_entitlement_ledger(account_id,user_id,event_type,available_delta,reserved_delta,reason_code,idempotency_key) values (?,?,'INITIAL_GRANT',?,0,'POLICY_GRANT',?)",
                    accountId, saved.getId(), granted, "INITIAL_GRANT:" + saved.getId());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public TenantVisitEntitlementView getMine(Long userId) {
        List<TenantVisitEntitlementView> rows = jdbc.query(
                "select total_granted_credits,available_credits,reserved_credits,reconciliation_required from tenant_visit_entitlement_accounts where user_id=?",
                (rs, row) -> {
                    int available = rs.getInt("available_credits");
                    int reserved = rs.getInt("reserved_credits");
                    Integer total = (Integer) rs.getObject("total_granted_credits");
                    boolean unresolved = rs.getBoolean("reconciliation_required") || total == null
                            || (long) available + reserved > total;
                    return new TenantVisitEntitlementView(unresolved ? null : total,
                            available + reserved, reserved, available, unresolved);
                }, userId);
        return rows.isEmpty() ? new TenantVisitEntitlementView(null, null, null, null, true) : rows.get(0);
    }
}
