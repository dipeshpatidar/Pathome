package com.indore.pathome.spaces.exception;

/**
 * Thrown when an optimistic concurrency conflict occurs while saving a draft.
 */
public class DraftConflictException extends RuntimeException {

    private final String draftId;
    private final int serverVersion;

    public DraftConflictException(String draftId, int serverVersion, String message) {
        super(message);
        this.draftId = draftId;
        this.serverVersion = serverVersion;
    }

    public String getDraftId() {
        return draftId;
    }

    public int getServerVersion() {
        return serverVersion;
    }
}
