package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.PropertyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Public-safe pre-submission shape: no private address, owner contact, or coordinates. */
public record LandlordPreview(String title, PropertyType propertyType, String bhkCount,
                              String city, String locality, BigDecimal monthlyRent,
                              BigDecimal securityDeposit, LocalDate availableFrom,
                              String furnishingStatus, Double totalAreaSqFt,
                              String description, List<LandlordMediaItem> media,
                              List<String> missingRequirements) {}
