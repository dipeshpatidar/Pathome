package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.PropertyType;
import java.math.BigDecimal;

/** Public-safe, structured choice for the rental search combobox. */
public record PublicSearchSuggestion(
        String type,
        String label,
        String city,
        String locality,
        String bhk,
        PropertyType propertyType,
        String furnishing,
        BigDecimal minRent,
        BigDecimal maxRent,
        long resultCount
) {}
