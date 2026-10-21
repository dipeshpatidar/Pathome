package com.indore.pathome.spaces.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "lead_preferred_localities", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lead_preferred_locality", columnNames = {"lead_id", "locality_id"})
})
public class LeadPreferredLocality {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "locality_id", nullable = false)
    private Locality locality;

    protected LeadPreferredLocality() {}

    public LeadPreferredLocality(LeadRoutingQueue lead, Locality locality) {
        this.lead = lead;
        this.locality = locality;
    }

    public Locality getLocality() { return locality; }
}
