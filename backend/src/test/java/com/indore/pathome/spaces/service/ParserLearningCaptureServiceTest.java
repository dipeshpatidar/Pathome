package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ParserLearningCaptureServiceTest {

    @Mock
    private ParserLearningService parserLearningService;

    @org.junit.jupiter.api.BeforeEach
    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void capturesImmediatelyWhenPublishingTransactionAlreadyCommitted() {
        ParsedPropertyDTO property = new ParsedPropertyDTO();
        ParserLearningCaptureService service = new ParserLearningCaptureService(parserLearningService);

        service.captureAfterSuccessfulPublish(property, 42L);

        verify(parserLearningService).recordPublishedReview(property, 42L);
    }

    @Test
    void defersCaptureUntilThePublishingTransactionCommits() {
        ParsedPropertyDTO property = new ParsedPropertyDTO();
        ParserLearningCaptureService service = new ParserLearningCaptureService(parserLearningService);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.captureAfterSuccessfulPublish(property, 84L);

        verify(parserLearningService, never()).recordPublishedReview(property, 84L);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(parserLearningService).recordPublishedReview(property, 84L);
    }

    @Test
    void doesNotCaptureWhenThePublishingTransactionRollsBack() {
        ParsedPropertyDTO property = new ParsedPropertyDTO();
        ParserLearningCaptureService service = new ParserLearningCaptureService(parserLearningService);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.captureAfterSuccessfulPublish(property, 126L);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(parserLearningService, never()).recordPublishedReview(property, 126L);
    }
}
