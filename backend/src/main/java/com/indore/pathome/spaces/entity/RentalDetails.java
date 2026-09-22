package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rental_details", indexes = {
    @Index(name = "idx_rental_available_from", columnList = "available_from")
})
@DiscriminatorValue("RENTAL")
public class RentalDetails extends Listing {

    @Column(name = "monthly_rent", nullable = false)
    private BigDecimal monthlyRent;

    @Column(name = "security_deposit", nullable = false)
    private BigDecimal securityDeposit;

    @Column(name = "maintenance_charge")
    private BigDecimal maintenanceCharge;

    @Column(name = "brokerage_amount")
    private BigDecimal brokerageAmount;

    @Column(name = "brokerage_days")
    private Integer brokerageDays;

    @Column(name = "security_deposit_months")
    private Integer securityDepositMonths;

    @Column(name = "bachelor_allowed")
    private Boolean bachelorAllowed = true;

    @Column(name = "preferred_tenant")
    private String preferredTenant;

    @Column(name = "available_from")
    private LocalDateTime availableFrom;

    public RentalDetails() {
        setListingType(ListingType.RENT);
    }

    public BigDecimal getMonthlyRent() { return monthlyRent; }
    public void setMonthlyRent(BigDecimal monthlyRent) { this.monthlyRent = monthlyRent; }

    public BigDecimal getSecurityDeposit() { return securityDeposit; }
    public void setSecurityDeposit(BigDecimal securityDeposit) { this.securityDeposit = securityDeposit; }

    public BigDecimal getMaintenanceCharge() { return maintenanceCharge; }
    public void setMaintenanceCharge(BigDecimal maintenanceCharge) { this.maintenanceCharge = maintenanceCharge; }

    public BigDecimal getBrokerageAmount() { return brokerageAmount; }
    public void setBrokerageAmount(BigDecimal brokerageAmount) { this.brokerageAmount = brokerageAmount; }

    public Integer getBrokerageDays() { return brokerageDays; }
    public void setBrokerageDays(Integer brokerageDays) { this.brokerageDays = brokerageDays; }

    public Integer getSecurityDepositMonths() { return securityDepositMonths; }
    public void setSecurityDepositMonths(Integer securityDepositMonths) { this.securityDepositMonths = securityDepositMonths; }

    public Boolean getBachelorAllowed() { return bachelorAllowed; }
    public void setBachelorAllowed(Boolean bachelorAllowed) { this.bachelorAllowed = bachelorAllowed; }

    public String getPreferredTenant() { return preferredTenant; }
    public void setPreferredTenant(String preferredTenant) { this.preferredTenant = preferredTenant; }

    public LocalDateTime getAvailableFrom() { return availableFrom; }
    public void setAvailableFrom(LocalDateTime availableFrom) { this.availableFrom = availableFrom; }
}
