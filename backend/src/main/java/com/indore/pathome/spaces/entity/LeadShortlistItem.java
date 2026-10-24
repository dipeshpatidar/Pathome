package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
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
@Table(name = "lead_shortlist_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_lead_shortlist_item", columnNames = {"shortlist_id", "listing_id"}),
        @UniqueConstraint(name = "uk_lead_shortlist_position", columnNames = {"shortlist_id", "position"})
})
public class LeadShortlistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shortlist_id", nullable = false)
    private LeadShortlist shortlist;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(nullable = false)
    private Integer position;

    protected LeadShortlistItem() {}

    public LeadShortlistItem(LeadShortlist shortlist, Long listingId, Integer position) {
        this.shortlist = shortlist;
        this.listingId = listingId;
        this.position = position;
    }

    public LeadShortlist getShortlist() { return shortlist; }
    public Long getListingId() { return listingId; }
    public Integer getPosition() { return position; }
}
