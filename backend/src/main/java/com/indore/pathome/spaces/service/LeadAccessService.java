package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class LeadAccessService {
    private final StaffAccessService access;

    public LeadAccessService(StaffAccessService access) {
        this.access = access;
    }

    @Transactional(readOnly = true)
    public LeadAccessContext context(Long actorId) {
        User actor = access.requireAttributableUser(actorId);
        List<StaffAccessGrant> grants = access.effectiveGrants(actor.getId());
        boolean globalAdmin = false;
        List<Long> intakeCities = new ArrayList<>();
        List<Long> routingCities = new ArrayList<>();
        List<Long> ownedTeams = new ArrayList<>();
        List<Long> supervisedTeams = new ArrayList<>();
        for (StaffAccessGrant grant : grants) {
            StaffCapability capability = grant.getCapability();
            StaffScopeType scope = grant.getScopeType();
            if (capability == StaffCapability.STAFF_ADMIN && scope == StaffScopeType.GLOBAL) {
                globalAdmin = true;
            } else if (capability == StaffCapability.OPS_INTAKE && grant.getCity() != null) {
                intakeCities.add(grant.getCity().getId());
            } else if (capability == StaffCapability.CITY_TEAM_ADMIN && grant.getCity() != null) {
                routingCities.add(grant.getCity().getId());
            } else if (capability == StaffCapability.OPS_COORDINATE && grant.getTeam() != null) {
                ownedTeams.add(grant.getTeam().getId());
            } else if (capability == StaffCapability.OPS_SUPERVISE && grant.getTeam() != null) {
                supervisedTeams.add(grant.getTeam().getId());
            }
        }
        LeadAccessContext context = new LeadAccessContext(globalAdmin,
                intakeCities.stream().distinct().sorted().toList(),
                routingCities.stream().distinct().sorted().toList(),
                ownedTeams.stream().distinct().sorted().toList(),
                supervisedTeams.stream().distinct().sorted().toList());
        if (!context.canList()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Current Lead authority is required");
        }
        return context;
    }

    void requireReadable(LeadAccessContext context, LeadRoutingQueue lead) {
        if (!context.canRead(lead)) throw LeadAccessContext.hidden();
    }

    void requireEditable(LeadAccessContext context, LeadRoutingQueue lead, Long actorId) {
        if (!context.canEdit(lead, actorId)) throw LeadAccessContext.hidden();
    }

    void requireFetchableCardinality(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " is required");
    }
}
