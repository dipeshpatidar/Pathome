package com.indore.pathome.spaces.service;

import java.time.Duration;

/** Approved A2 enquiry-phone proof parameters, isolated as testable policy. */
public final class LeadPhoneVerificationPolicy {
    public static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    public static final Duration PROOF_LIFETIME = Duration.ofHours(24);
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int ACTOR_PER_MINUTE = 20;
    public static final int ACTOR_PER_HOUR = 200;
    public static final int DESTINATION_PER_HOUR = 3;
    public static final int DESTINATION_PER_DAY = 10;

    private LeadPhoneVerificationPolicy() {}

    public static boolean actorSendAllowed(long minuteCount, long hourCount) {
        return minuteCount < ACTOR_PER_MINUTE && hourCount < ACTOR_PER_HOUR;
    }

    public static boolean destinationSendAllowed(long hourCount, long dayCount) {
        return hourCount < DESTINATION_PER_HOUR && dayCount < DESTINATION_PER_DAY;
    }
}
