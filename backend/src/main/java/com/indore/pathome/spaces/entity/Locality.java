package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "localities", uniqueConstraints = {
    @UniqueConstraint(name = "uk_locality_city_sector", columnNames = {"city", "sectorName"})
}, indexes = {
    @Index(name = "idx_locality_city_sector", columnList = "city, sectorName"),
    @Index(name = "idx_locality_sector", columnList = "sectorName")
})
public class Locality {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supported_city_id")
    private SupportedCity supportedCity;

    @Column(nullable = false)
    private String sectorName;

    private String aliases;

    private Integer demandScore;

    private Double avgRentAmount;

    private LocalDateTime createdAt;

    public Locality() {
        this.createdAt = LocalDateTime.now();
    }

    public Locality(String city, String sectorName, String aliases, Integer demandScore, Double avgRentAmount) {
        this.city = city;
        this.sectorName = sectorName;
        this.aliases = aliases;
        this.demandScore = demandScore;
        this.avgRentAmount = avgRentAmount;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public SupportedCity getSupportedCity() {
        return supportedCity;
    }

    public void setSupportedCity(SupportedCity supportedCity) {
        this.supportedCity = supportedCity;
    }

    public String getSectorName() {
        return sectorName;
    }

    public void setSectorName(String sectorName) {
        this.sectorName = sectorName;
    }

    public String getAliases() {
        return aliases;
    }

    public void setAliases(String aliases) {
        this.aliases = aliases;
    }

    public Integer getDemandScore() {
        return demandScore;
    }

    public void setDemandScore(Integer demandScore) {
        this.demandScore = demandScore;
    }

    public Double getAvgRentAmount() {
        return avgRentAmount;
    }

    public void setAvgRentAmount(Double avgRentAmount) {
        this.avgRentAmount = avgRentAmount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
