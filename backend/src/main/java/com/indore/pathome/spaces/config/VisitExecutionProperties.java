package com.indore.pathome.spaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pathome.visit")
public class VisitExecutionProperties {
    private int autoShiftThresholdMinutes = 10;
    private int reservationHorizonDays = 7;
    private int repairHorizonHours = 4;
    private int maximumDownstreamSessions = 3;
    private int repairRetries = 2;
    private int alternateGeCandidates = 5;
    private int tenantNoShowGraceMinutes = 15;
    private int minimumContactAttempts = 2;
    private int contactAttemptSpacingMinutes = 5;
    private int tenantDisputeWindowHours = 24;
    private int silentOverrunAlertMinutes = 15;
    private int maximumOperationalRestoresPerDay = 3;
    private int incompleteOutcomeGraceMinutes = 60;
    private final Otp otp = new Otp();

    public int getAutoShiftThresholdMinutes() { return autoShiftThresholdMinutes; }
    public void setAutoShiftThresholdMinutes(int value) { autoShiftThresholdMinutes = positive(value, "autoShiftThresholdMinutes"); }
    public int getReservationHorizonDays() { return reservationHorizonDays; }
    public void setReservationHorizonDays(int value) { reservationHorizonDays = positive(value, "reservationHorizonDays"); }
    public int getRepairHorizonHours() { return repairHorizonHours; }
    public void setRepairHorizonHours(int value) { repairHorizonHours = positive(value, "repairHorizonHours"); }
    public int getMaximumDownstreamSessions() { return maximumDownstreamSessions; }
    public void setMaximumDownstreamSessions(int value) { maximumDownstreamSessions = positive(value, "maximumDownstreamSessions"); }
    public int getRepairRetries() { return repairRetries; }
    public void setRepairRetries(int value) { repairRetries = positive(value, "repairRetries"); }
    public int getAlternateGeCandidates() { return alternateGeCandidates; }
    public void setAlternateGeCandidates(int value) { alternateGeCandidates = positive(value, "alternateGeCandidates"); }
    public int getTenantNoShowGraceMinutes() { return tenantNoShowGraceMinutes; }
    public void setTenantNoShowGraceMinutes(int value) { tenantNoShowGraceMinutes = positive(value, "tenantNoShowGraceMinutes"); }
    public int getMinimumContactAttempts() { return minimumContactAttempts; }
    public void setMinimumContactAttempts(int value) { minimumContactAttempts = positive(value, "minimumContactAttempts"); }
    public int getContactAttemptSpacingMinutes() { return contactAttemptSpacingMinutes; }
    public void setContactAttemptSpacingMinutes(int value) { contactAttemptSpacingMinutes = positive(value, "contactAttemptSpacingMinutes"); }
    public int getTenantDisputeWindowHours() { return tenantDisputeWindowHours; }
    public void setTenantDisputeWindowHours(int value) { tenantDisputeWindowHours = positive(value, "tenantDisputeWindowHours"); }
    public int getSilentOverrunAlertMinutes() { return silentOverrunAlertMinutes; }
    public void setSilentOverrunAlertMinutes(int value) { silentOverrunAlertMinutes = positive(value, "silentOverrunAlertMinutes"); }
    public int getMaximumOperationalRestoresPerDay() { return maximumOperationalRestoresPerDay; }
    public void setMaximumOperationalRestoresPerDay(int value) { maximumOperationalRestoresPerDay = positive(value, "maximumOperationalRestoresPerDay"); }
    public int getIncompleteOutcomeGraceMinutes() { return incompleteOutcomeGraceMinutes; }
    public void setIncompleteOutcomeGraceMinutes(int value) { incompleteOutcomeGraceMinutes = positive(value, "incompleteOutcomeGraceMinutes"); }
    public Otp getOtp() { return otp; }

    private static int positive(int value, String name) {
        if (value < 1 || value > 10000) throw new IllegalArgumentException(name + " must be within 1..10000");
        return value;
    }

    public static class Otp {
        private int digits = 6;
        private int validityMinutes = 10;
        private int regenerationCooldownSeconds = 30;
        private int maximumAttempts = 5;
        private String keyId = "primary";
        private String hmacSecret = "";
        private String previousKeyId = "";
        private String previousHmacSecret = "";
        public int getDigits() { return digits; }
        public void setDigits(int value) { if (value != 6) throw new IllegalArgumentException("Visit OTP must use six digits"); digits = value; }
        public int getValidityMinutes() { return validityMinutes; }
        public void setValidityMinutes(int value) { if (value < 1 || value > 60) throw new IllegalArgumentException("OTP validity must be 1..60 minutes"); validityMinutes = value; }
        public int getRegenerationCooldownSeconds() { return regenerationCooldownSeconds; }
        public void setRegenerationCooldownSeconds(int value) { if (value < 1 || value > 300) throw new IllegalArgumentException("OTP regeneration cooldown must be 1..300 seconds"); regenerationCooldownSeconds = value; }
        public int getMaximumAttempts() { return maximumAttempts; }
        public void setMaximumAttempts(int value) { if (value < 1 || value > 10) throw new IllegalArgumentException("OTP maximum attempts must be 1..10"); maximumAttempts = value; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String value) { if (value == null || value.isBlank() || value.length() > 64) throw new IllegalArgumentException("OTP key ID is required"); keyId = value; }
        public String getHmacSecret() { return hmacSecret; }
        public void setHmacSecret(String value) { hmacSecret = value == null ? "" : value; }
        public String getPreviousKeyId() { return previousKeyId; }
        public void setPreviousKeyId(String value) { previousKeyId = value == null ? "" : value; }
        public String getPreviousHmacSecret() { return previousHmacSecret; }
        public void setPreviousHmacSecret(String value) { previousHmacSecret = value == null ? "" : value; }
        public String secretForKey(String requestedKeyId) {
            if (keyId.equals(requestedKeyId)) return hmacSecret;
            if (previousKeyId.equals(requestedKeyId)) return previousHmacSecret;
            return "";
        }
    }
}
