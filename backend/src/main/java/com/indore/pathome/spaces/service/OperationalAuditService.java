package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.AuditActorKind;
import com.indore.pathome.spaces.entity.OperationalAuditEvent;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.OperationalAuditEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class OperationalAuditService {
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Set<String> ALLOWED_DETAILS = Set.of(
            "capability", "scopeType", "cityId", "teamId", "staffActive", "provisioningSource");

    private final OperationalAuditEventRepository events;
    private final DatabaseClock databaseClock;

    public OperationalAuditService(OperationalAuditEventRepository events, DatabaseClock databaseClock) {
        this.events = events;
        this.databaseClock = databaseClock;
    }

    public OperationalAuditEvent recordUserEvent(User actor, String actionCode, String targetType, Long targetId,
                                                  String reasonCode, SupportedCity city, OperatingTeam team,
                                                  Map<String, Object> details) {
        validateCodes(actionCode, targetType, reasonCode);
        OperationalAuditEvent event = new OperationalAuditEvent(databaseClock.now(), AuditActorKind.USER, actor,
                null, actionCode, targetType, targetId, city, team, null, null, reasonCode,
                sanitizeDetails(details));
        return events.save(event);
    }

    public OperationalAuditEvent recordDeploymentOperatorEvent(String operatorReference, String actionCode,
                                                                 String targetType, Long targetId,
                                                                 String reasonCode, SupportedCity city,
                                                                 OperatingTeam team,
                                                                 Map<String, Object> details) {
        validateCodes(actionCode, targetType, reasonCode);
        if (operatorReference == null || !operatorReference.matches("[A-Z0-9][A-Z0-9:_-]{0,119}")) {
            throw new IllegalArgumentException("A bounded deployment operator reference is required");
        }
        OperationalAuditEvent event = new OperationalAuditEvent(databaseClock.now(),
                AuditActorKind.DEPLOYMENT_OPERATOR, null, operatorReference, actionCode, targetType, targetId,
                city, team, null, null, reasonCode, sanitizeDetails(details));
        return events.save(event);
    }

    private static Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (!ALLOWED_DETAILS.contains(key)) {
                throw new IllegalArgumentException("Audit details contain an unsupported field");
            }
            if (value instanceof String string && (string.length() > 64 || !CODE.matcher(string).matches())) {
                throw new IllegalArgumentException("Audit detail codes must be bounded identifiers");
            }
            if (!(value instanceof String || value instanceof Long || value instanceof Integer || value instanceof Boolean)) {
                throw new IllegalArgumentException("Audit details accept only bounded codes, IDs, and flags");
            }
            safe.put(key, value);
        });
        return Map.copyOf(safe);
    }

    private static void validateCodes(String actionCode, String targetType, String reasonCode) {
        if (!validCode(actionCode) || targetType == null || !targetType.matches("[A-Z][A-Z0-9_]{0,47}")
                || !validCode(reasonCode)) {
            throw new IllegalArgumentException("Audit action, target, and reason must use bounded codes");
        }
    }

    private static boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }
}
