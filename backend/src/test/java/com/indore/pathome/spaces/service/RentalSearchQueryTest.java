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

    @Test
    void partialPriceQueriesPreserveStructuredFiltersWithoutPollutingLocation() {
        // 1. "2bhk flat under" -> expecting max rent, location is clean, maxRent is null
        RentalSearchQuery q1 = RentalSearchQuery.parse("2bhk flat under");
        assertEquals("2BHK", q1.bhkKey());
        assertEquals(PropertyType.FLAT, q1.propertyType());
        assertEquals("", q1.location());
        assertNull(q1.maxRent());
        assertEquals(RentalSearchQuery.PriceState.EXPECTING_MAX_RENT, q1.priceState());

        // 2. "2bhk flat under 2" -> partial numeric entry, maxRent is null, location clean
        RentalSearchQuery q2 = RentalSearchQuery.parse("2bhk flat under 2");
        assertEquals("2BHK", q2.bhkKey());
        assertEquals(PropertyType.FLAT, q2.propertyType());
        assertEquals("", q2.location());
        assertNull(q2.maxRent());
        assertEquals(RentalSearchQuery.PriceState.EXPECTING_MAX_RENT, q2.priceState());

        // 3. "2bhk flat under 20" -> partial numeric entry, maxRent is null
        RentalSearchQuery q3 = RentalSearchQuery.parse("2bhk flat under 20");
        assertEquals("2BHK", q3.bhkKey());
        assertEquals(PropertyType.FLAT, q3.propertyType());
        assertEquals("", q3.location());
        assertNull(q3.maxRent());
        assertEquals(RentalSearchQuery.PriceState.EXPECTING_MAX_RENT, q3.priceState());

        // 4. "2bhk flat under 200" -> partial numeric entry, maxRent is null
        RentalSearchQuery q4 = RentalSearchQuery.parse("2bhk flat under 200");
        assertEquals("2BHK", q4.bhkKey());
        assertEquals(PropertyType.FLAT, q4.propertyType());
        assertEquals("", q4.location());
        assertNull(q4.maxRent());
        assertEquals(RentalSearchQuery.PriceState.EXPECTING_MAX_RENT, q4.priceState());

        // 5. "2bhk flat under 20000" -> complete price entry
        RentalSearchQuery q5 = RentalSearchQuery.parse("2bhk flat under 20000");
        assertEquals("2BHK", q5.bhkKey());
        assertEquals(PropertyType.FLAT, q5.propertyType());
        assertEquals("", q5.location());
        assertEquals(new BigDecimal("20000"), q5.maxRent());
        assertEquals(RentalSearchQuery.PriceState.COMPLETE, q5.priceState());

        // 6. "2bhk flat under 20k" -> complete price entry with unit
        RentalSearchQuery q6 = RentalSearchQuery.parse("2bhk flat under 20k");
        assertEquals("2BHK", q6.bhkKey());
        assertEquals(PropertyType.FLAT, q6.propertyType());
        assertEquals("", q6.location());
        assertEquals(new BigDecimal("20000"), q6.maxRent());
        assertEquals(RentalSearchQuery.PriceState.COMPLETE, q6.priceState());
    }

    @Test
    void partialConnectorQueriesDoNotTreatConnectorsAsLocation() {
        // "2bhk flat in" -> expecting location, location is clean
        RentalSearchQuery inQuery = RentalSearchQuery.parse("2bhk flat in");
        assertEquals("2BHK", inQuery.bhkKey());
        assertEquals(PropertyType.FLAT, inQuery.propertyType());
        assertEquals("", inQuery.location());
        assertEquals(RentalSearchQuery.LocationState.EXPECTING_LOCATION, inQuery.locationState());

        // "2bhk flat at" -> expecting location
        RentalSearchQuery atQuery = RentalSearchQuery.parse("2bhk flat at");
        assertEquals("2BHK", atQuery.bhkKey());
        assertEquals(PropertyType.FLAT, atQuery.propertyType());
        assertEquals("", atQuery.location());
        assertEquals(RentalSearchQuery.LocationState.EXPECTING_LOCATION, atQuery.locationState());

        // "2bhk flat in vij" -> provided partial location
        RentalSearchQuery vijQuery = RentalSearchQuery.parse("2bhk flat in vij");
        assertEquals("2BHK", vijQuery.bhkKey());
        assertEquals(PropertyType.FLAT, vijQuery.propertyType());
        assertEquals("vij", vijQuery.location());
        assertEquals(RentalSearchQuery.LocationState.PROVIDED, vijQuery.locationState());

        // "2bhk flat in vijaynagr" -> provided typo location
        RentalSearchQuery typoQuery = RentalSearchQuery.parse("2bhk flat in vijaynagr");
        assertEquals("2BHK", typoQuery.bhkKey());
        assertEquals(PropertyType.FLAT, typoQuery.propertyType());
        assertEquals("vijaynagr", typoQuery.location());
        assertEquals(RentalSearchQuery.LocationState.PROVIDED, typoQuery.locationState());

        // "2bhk flat between 20k and" -> expecting range end
        RentalSearchQuery betweenQuery = RentalSearchQuery.parse("2bhk flat between 20k and");
        assertEquals("2BHK", betweenQuery.bhkKey());
        assertEquals(PropertyType.FLAT, betweenQuery.propertyType());
        assertEquals("", betweenQuery.location());
        assertEquals(new BigDecimal("20000"), betweenQuery.minRent());
        assertNull(betweenQuery.maxRent());
        assertEquals(RentalSearchQuery.PriceState.EXPECTING_RANGE_END, betweenQuery.priceState());
    }

    @Test
    void recognizes1RkInAllCommonSyntaxes() {
        // "1rk"
        RentalSearchQuery rk1 = RentalSearchQuery.parse("1rk");
        assertEquals("1RK", rk1.bhkKey());
        assertEquals("1 RK", rk1.bhkLabel());
        assertEquals("", rk1.location());

        // "1 rk"
        RentalSearchQuery rk2 = RentalSearchQuery.parse("1 rk");
        assertEquals("1RK", rk2.bhkKey());
        assertEquals("1 RK", rk2.bhkLabel());
        assertEquals("", rk2.location());

        // "1RK"
        RentalSearchQuery rk3 = RentalSearchQuery.parse("1RK");
        assertEquals("1RK", rk3.bhkKey());
        assertEquals("1 RK", rk3.bhkLabel());
        assertEquals("", rk3.location());

        // "rk"
        RentalSearchQuery rk4 = RentalSearchQuery.parse("rk");
        assertEquals("1RK", rk4.bhkKey());
        assertEquals("1 RK", rk4.bhkLabel());
        assertEquals("", rk4.location());

        // "1rk in vijay nagar"
        RentalSearchQuery rk5 = RentalSearchQuery.parse("1rk in vijay nagar");
        assertEquals("1RK", rk5.bhkKey());
        assertEquals("1 RK", rk5.bhkLabel());
        assertEquals("vijay nagar", rk5.location());
    }

    @Test
    void parsesExplicitCityInConnectedAndStructuredQueries() {
        // "2bhk flat in pune" -> bhk=2BHK, type=FLAT, explicitCity=Pune, location=""
        RentalSearchQuery q1 = RentalSearchQuery.parse("2bhk flat in pune");
        assertEquals("2BHK", q1.bhkKey());
        assertEquals(PropertyType.FLAT, q1.propertyType());
        assertEquals("Pune", q1.explicitCity());
        assertEquals("", q1.location());

        // "2bhk in indore" -> bhk=2BHK, explicitCity=Indore, location=""
        RentalSearchQuery q2 = RentalSearchQuery.parse("2bhk in indore");
        assertEquals("2BHK", q2.bhkKey());
        assertEquals("Indore", q2.explicitCity());
        assertEquals("", q2.location());

        // "pune 2bhk" -> bhk=2BHK, explicitCity=Pune, location=""
        RentalSearchQuery q3 = RentalSearchQuery.parse("pune 2bhk");
        assertEquals("2BHK", q3.bhkKey());
        assertEquals("Pune", q3.explicitCity());
        assertEquals("", q3.location());

        // "2bhk flat in mumbai" -> bhk=2BHK, type=FLAT, explicitCity=Mumbai (parsed regardless of support)
        RentalSearchQuery q4 = RentalSearchQuery.parse("2bhk flat in mumbai");
        assertEquals("2BHK", q4.bhkKey());
        assertEquals(PropertyType.FLAT, q4.propertyType());
        assertEquals("Mumbai", q4.explicitCity());
        assertEquals("", q4.location());

        // "3bhk baner" -> bhk=3BHK, explicitCity=null, location="baner" (resolved by location resolver)
        RentalSearchQuery q5 = RentalSearchQuery.parse("3bhk baner");
        assertEquals("3BHK", q5.bhkKey());
        assertNull(q5.explicitCity());
        assertEquals("baner", q5.location());

        // "Pune" (standalone, no other context) -> explicitCity=null, location="Pune"
        RentalSearchQuery q6 = RentalSearchQuery.parse("Pune");
        assertNull(q6.bhkKey());
        assertNull(q6.explicitCity());
        assertEquals("Pune", q6.location());
    }
}
