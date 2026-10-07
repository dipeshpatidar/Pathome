package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OperationalNotificationAuthorizationService {
    private final VisitSessionRepository sessions;
    private final OperationalSecurityGuards guards;

    public OperationalNotificationAuthorizationService(VisitSessionRepository sessions,
                                                        OperationalSecurityGuards guards) {
        this.sessions = sessions;
        this.guards = guards;
    }

    /** Session lock, ordered security guards, then a database-time visibility check. */
    @Transactional
    public boolean lockAndCanDeliver(Long recipientUserId, Long sessionId, String recipientRole) {
        if (recipientUserId == null || recipientUserId <= 0 || sessionId == null || sessionId <= 0
                || recipientRole == null) return false;
        VisitSession session = sessions.findLockedById(sessionId).orElse(null);
        if (session == null) return false;
        Long cityId = session.getSupportedCity() == null ? null : session.getSupportedCity().getId();
        Long teamId = session.getOperatingTeam() == null ? null : session.getOperatingTeam().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), List.of(recipientUserId));
        return sessions.isVisibleForNotification(recipientUserId, sessionId, recipientRole);
    }
}
