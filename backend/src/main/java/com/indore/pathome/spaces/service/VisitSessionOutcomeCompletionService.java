package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CompleteVisitSessionWithOutcomesCommand;
import com.indore.pathome.spaces.dto.GroundVisitSessionOutcomeView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VisitSessionOutcomeCompletionService {
    private final VisitSessionOutcomeService outcomes;
    private final VisitExecutionService execution;

    public VisitSessionOutcomeCompletionService(VisitSessionOutcomeService outcomes, VisitExecutionService execution) {
        this.outcomes = outcomes;
        this.execution = execution;
    }

    @Transactional
    public GroundVisitSessionOutcomeView complete(Long groundExecutiveId, Long sessionId,
            CompleteVisitSessionWithOutcomesCommand command) {
        GroundVisitSessionOutcomeView prepared = outcomes.prepareCombinedCompletion(
                groundExecutiveId, sessionId, command);
        if ("FINALIZED".equals(prepared.reportState().name())) return prepared;
        if ("STARTED".equals(prepared.sessionState())) execution.finish(groundExecutiveId, sessionId);
        return outcomes.finalizeCombinedCompletion(groundExecutiveId, sessionId, command);
    }
}
