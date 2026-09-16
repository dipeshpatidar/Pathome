package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Service
public class ParserLearningCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ParserLearningCaptureService.class);

    private final ParserLearningService parserLearningService;

    public ParserLearningCaptureService(ParserLearningService parserLearningService) {
        this.parserLearningService = Objects.requireNonNull(parserLearningService);
    }

    public void captureAfterSuccessfulPublish(ParsedPropertyDTO property, Long listingId) {
        Objects.requireNonNull(property, "Published property must not be null");
        Objects.requireNonNull(listingId, "Published listing id must not be null");

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            captureSafely(property, listingId);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("Parser learning capture skipped for property {} because commit synchronization is unavailable",
                    listingId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                captureSafely(property, listingId);
            }
        });
    }

    private void captureSafely(ParsedPropertyDTO property, Long listingId) {
        try {
            parserLearningService.recordPublishedReview(property, listingId);
        } catch (RuntimeException exception) {
            log.warn("Published property {} could not be added to the learning queue: {}",
                    listingId, exception.getMessage());
        }
    }
}
