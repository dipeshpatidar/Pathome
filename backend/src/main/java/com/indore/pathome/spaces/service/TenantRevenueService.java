package com.indore.pathome.spaces.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * High-precision financial service calculating tenant commitment deposits, brokerage fees,
 * landlord commissions, and cashback rewards based on Indore market standards.
 */
@Service
public class TenantRevenueService {

    private static final BigDecimal COMMITMENT_DEPOSIT = new BigDecimal("100.00");
    private static final BigDecimal TENANT_BROKERAGE_PERCENT = new BigDecimal("0.50");
    private static final BigDecimal LANDLORD_COMMISSION_DAYS_RATIO = new BigDecimal("15")
            .divide(new BigDecimal("30"), 4, RoundingMode.HALF_UP);
    private static final BigDecimal RENT_AGREEMENT_CASHBACK_REWARD = new BigDecimal("1000.00");
    private static final int FREE_VISIT_THRESHOLD = 5;

    /**
     * Checks if visit requires a refundable commitment deposit (6th visit onwards).
     */
    public boolean requiresCommitmentDeposit(int visitSeq) {
        if (visitSeq < 1) {
            throw new IllegalArgumentException("Visit sequence number must be positive");
        }
        return visitSeq > FREE_VISIT_THRESHOLD;
    }

    /**
     * Returns the deposit amount required for visit sequence.
     */
    public BigDecimal getCommitmentDepositAmount(int visitSeq) {
        return requiresCommitmentDeposit(visitSeq) ? COMMITMENT_DEPOSIT : BigDecimal.ZERO;
    }

    /**
     * Calculates tenant brokerage fee (0% for first 5 visits, 50% rent from 6th visit onwards).
     */
    public BigDecimal calculateTenantBrokerage(int visitSeq, BigDecimal monthlyRent) {
        if (visitSeq <= FREE_VISIT_THRESHOLD || monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return monthlyRent.multiply(TENANT_BROKERAGE_PERCENT).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates landlord fee based on Indore market norms (15 days' rent).
     */
    public BigDecimal calculateLandlordCommission(BigDecimal monthlyRent) {
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return monthlyRent.multiply(LANDLORD_COMMISSION_DAYS_RATIO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Gets cashback reward upon signed rent agreement upload.
     */
    public BigDecimal getAgreementUploadCashbackReward() {
        return RENT_AGREEMENT_CASHBACK_REWARD;
    }
}