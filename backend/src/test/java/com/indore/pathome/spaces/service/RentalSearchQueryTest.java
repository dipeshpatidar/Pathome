package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RentalSearchQueryTest {
    @Test
    void parsesBhkWithOrWithoutSpacesAndLocationPrepositions() {
        RentalSearchQuery first = RentalSearchQuery.parse("  4bhk   in   Vijay   Nagar ");
        assertEquals("4BHK", first.bhkKey());
        assertEquals("4 BHK", first.bhkLabel());
        assertEquals("Vijay Nagar", first.location());

        RentalSearchQuery second = RentalSearchQuery.parse("2 bhk near Bombay Hospital");
        assertEquals("2BHK", second.bhkKey());
        assertEquals("Bombay Hospital", second.location());

        RentalSearchQuery third = RentalSearchQuery.parse("1RK in Bhawarkua");
        assertEquals("1RK", third.bhkKey());
        assertEquals("Bhawarkua", third.location());
    }

    @Test
    void acceptsLocationOnlyAndNormalizesWhitespaceAndCase() {
        assertEquals("Vijay Nagar", RentalSearchQuery.parse("  Vijay   Nagar  ").location());
        assertEquals("vijay nagar", RentalSearchQuery.normalizeLocation("  VIJAY   NAGAR  "));
        assertNull(RentalSearchQuery.parse("Pune").bhkKey());
        assertEquals("", RentalSearchQuery.parse(" ").location());
    }

    @Test
    void rejectsUnsupportedOrUnsafeStructuredFilters() {
        assertEquals("5BHK", RentalSearchQuery.normalizeBhk("5 bhk"));
        assertThrows(IllegalArgumentException.class, () -> RentalSearchQuery.normalizeBhk("5 bhk vijay"));
        assertThrows(IllegalArgumentException.class, () -> RentalSearchQuery.parse("vijay; drop table listings"));
        assertThrows(IllegalArgumentException.class, () -> RentalSearchQuery.parse("x".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> RentalSearchQuery.normalizeBhk("2 bhk flat"));
    }

    @Test
    void parsesRealPropertyTypesWithoutRemovingLocalityWords() {
        RentalSearchQuery flat = RentalSearchQuery.parse("2bhk flat in vijay nagar");
        assertEquals("2BHK", flat.bhkKey());
        assertEquals(PropertyType.FLAT, flat.propertyType());
        assertEquals("Flat", flat.propertyTypeLabel());
        assertEquals("vijay nagar", flat.location());

        RentalSearchQuery apartment = RentalSearchQuery.parse("2 bhk apartment vijay nagar");
        assertEquals(PropertyType.FLAT, apartment.propertyType());
        assertEquals("vijay nagar", apartment.location());
        assertEquals(PropertyType.HOUSE, RentalSearchQuery.parse("3bhk house nipania").propertyType());
        assertEquals(PropertyType.PENTHOUSE, RentalSearchQuery.parse("3bhk penthouse nipania").propertyType());
        assertEquals(PropertyType.STUDIO, RentalSearchQuery.parse("1bhk studio nipania").propertyType());
        assertEquals(PropertyType.SERVICED_APARTMENT,
                RentalSearchQuery.parse("2bhk serviced apartment nipania").propertyType());
    }

    @Test
    void recognizesOnlyKnownFlatPrefixesAndPreservesOtherLocations() {
        for (String prefix : new String[]{"f", "fl", "fla", "flat"}) {
            RentalSearchQuery parsed = RentalSearchQuery.parse("2bhk " + prefix);
            assertEquals(PropertyType.FLAT, parsed.propertyType());
            assertEquals("", parsed.location());
        }
        assertEquals("vijay nagar", RentalSearchQuery.parse("4bhk vijay nagar").location());
        assertEquals("vijay nagar", RentalSearchQuery.parse("4bhk flat vijay nagar").location());
        assertEquals("villa nipania", RentalSearchQuery.parse("2bhk villa nipania").location());
        assertNull(RentalSearchQuery.parse("Vijay Nagar").propertyType());
        assertEquals("Flat Road", RentalSearchQuery.parse("Flat Road").location());
    }

    @Test
    void v2QueryMatrixKeepsStructuredTermsSeparateFromLocation() {
        record Case(String input, String bhk, PropertyType type, String furnishing,
                    String min, String max, String location) {}
        Case[] cases = {
            new Case("2bhk", "2BHK", null, null, null, null, ""),
            new Case("2 bhk", "2BHK", null, null, null, null, ""),
            new Case("2bedroom", "2BHK", null, null, null, null, ""),
            new Case("2bed", "2BHK", null, null, null, null, ""),
            new Case("2bhk flat", "2BHK", PropertyType.FLAT, null, null, null, ""),
            new Case("flat 2bhk", "2BHK", PropertyType.FLAT, null, null, null, ""),
            new Case("2bhk flt", "2BHK", PropertyType.FLAT, null, null, null, ""),
            new Case("2bhk fla", "2BHK", PropertyType.FLAT, null, null, null, ""),
            new Case("2bhk flaat", "2BHK", PropertyType.FLAT, null, null, null, ""),
            new Case("2bhk vijay nagar", "2BHK", null, null, null, null, "vijay nagar"),
            new Case("2bhk vijaynagr", "2BHK", null, null, null, null, "vijaynagr"),
            new Case("vijaynagr 2bhk", "2BHK", null, null, null, null, "vijaynagr"),
            new Case("2bhk flat vijay nagar", "2BHK", PropertyType.FLAT, null, null, null, "vijay nagar"),
            new Case("vijay nagar 2bhk flat", "2BHK", PropertyType.FLAT, null, null, null, "vijay nagar"),
            new Case("2 bedroom apartment vijay nagar", "2BHK", PropertyType.FLAT, null, null, null, "vijay nagar"),
            new Case("3bhk nipania under 30k", "3BHK", null, null, null, "30000", "nipania"),
            new Case("3 bhk nipania below 30000", "3BHK", null, null, null, "30000", "nipania"),
            new Case("3bhk semi furnished nipania", "3BHK", null, "SEMI_FURNISHED", null, null, "nipania"),
            new Case("3bhk semi fur nipania", "3BHK", null, "SEMI_FURNISHED", null, null, "nipania"),
            new Case("flat under 25k vijay nagar", null, PropertyType.FLAT, null, null, "25000", "vijay nagar"),
            new Case("house bhawarkua", null, PropertyType.HOUSE, null, null, null, "bhawarkua"),
            new Case("scheme 78 2bhk", "2BHK", null, null, null, null, "scheme 78"),
            new Case("zzzznonexistent", null, null, null, null, null, "zzzznonexistent"),
            new Case("2bhk 20k to 30k", "2BHK", null, null, "20000", "30000", ""),
            new Case("2bhk between 20000 and 30000", "2BHK", null, null, "20000", "30000", ""),
            new Case("2bhk under 1.2 lakh", "2BHK", null, null, null, "120000", ""),
            new Case("2bhk up to ₹25k", "2BHK", null, null, null, "25000", ""),
            new Case("2bhk max inr 30k", "2BHK", null, null, null, "30000", "")
        };
        for (Case expected : cases) {
            RentalSearchQuery actual = RentalSearchQuery.parse(expected.input());
            assertEquals(expected.bhk(), actual.bhkKey(), expected.input());
            assertEquals(expected.type(), actual.propertyType(), expected.input());
            assertEquals(expected.furnishing(), actual.furnishingKey(), expected.input());
            assertEquals(expected.min() == null ? null : new BigDecimal(expected.min()), actual.minRent(), expected.input());
            assertEquals(expected.max() == null ? null : new BigDecimal(expected.max()), actual.maxRent(), expected.input());
            assertEquals(expected.location(), actual.location(), expected.input());
        }
    }

    @Test
    void rejectsInvalidRangeAndDoesNotTreatStandaloneLocalityNumberAsRent() {
        assertThrows(IllegalArgumentException.class, () -> RentalSearchQuery.parse("2bhk 30k to 20k"));
        assertNull(RentalSearchQuery.parse("scheme 78").maxRent());
        assertEquals("scheme 78", RentalSearchQuery.parse("scheme 78").location());
    }
}
