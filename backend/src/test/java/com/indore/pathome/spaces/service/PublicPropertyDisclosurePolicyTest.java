package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.SupportedCity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublicPropertyDisclosurePolicyTest {
    @Test
    void publicTitleUsesStructuredFactsAndNeverRawAuthoredText() {
        RentalDetails listing = new RentalDetails();
        listing.setBhkCount("2 BHK");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setSector("Vijay Nagar");
        listing.setCity("Indore");
        listing.setListingType(ListingType.RENT);
        listing.setTitle("Call Ramesh +91 98765 43210 at the private address");

        String title = PublicPropertyDisclosurePolicy.safeTitle(listing);

        assertEquals("2 BHK Flat", title);
        assertFalse(title.contains("Ramesh"));
        assertFalse(title.contains("98765"));
    }

    @Test
    void freeFormDescriptionAndCaptionsHaveNoFallbackContent() {
        RentalDetails listing = new RentalDetails();
        listing.setDescription("Owner mobile +91 98765 43210");

        assertNull(PublicPropertyDisclosurePolicy.safeDescription(listing));
    }

    @Test
    void amenitiesAllowKnownStructuredValuesAndDropArbitraryPrivateText() {
        assertEquals("Balcony, Lift",
                PublicPropertyDisclosurePolicy.safeAmenities(
                        "Balcony, Lift, Call Ramesh +91 98765 43210"));
        assertNull(PublicPropertyDisclosurePolicy.safeAmenities(
                "Private owner will meet at 22.7196, 75.8577"));
    }

    @Test
    void mediaPolicyRejectsPrivateAuthenticatedAndUnsafeUrlsButKeepsApprovedHttpsMedia() {
        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/home.webp",
                PublicPropertyDisclosurePolicy.safeMediaUrl(
                        "https://res.cloudinary.com/demo/image/upload/v1/home.webp"));
        assertNull(PublicPropertyDisclosurePolicy.safeMediaUrl(
                "https://res.cloudinary.com/demo/private/home.webp"));
        assertNull(PublicPropertyDisclosurePolicy.safeMediaUrl(
                "https://res.cloudinary.com/demo/authenticated/home.webp"));
        assertNull(PublicPropertyDisclosurePolicy.safeMediaUrl(
                "http://res.cloudinary.com/demo/image/upload/home.webp"));
        assertNull(PublicPropertyDisclosurePolicy.safeMediaUrl(
                "https://res.cloudinary.com/demo/image/upload/home.webp?token=secret"));
        assertNull(PublicPropertyDisclosurePolicy.safeMediaUrl(
                "https://127.0.0.1/image/upload/home.webp"));
        assertNull(PublicPropertyDisclosurePolicy.safeMediaUrl(
                "https://cdn.example/cover.webp"));
    }

    @Test
    void canonicalLocationAndKnownBhkAreUsedWithoutRawListingText() {
        RentalDetails listing = new RentalDetails();
        listing.setBhkCount("2 BHK");
        listing.setCity("Call owner 9876543210");
        listing.setSector("PRIVATE-STREET-SENTINEL");
        listing.setPropertyType(PropertyType.FLAT);
        SupportedCity city = new SupportedCity("indore", "Indore", true);
        Locality locality = new Locality("Unsafe raw city", "Vijay Nagar", null, 1, null);
        locality.setSupportedCity(city);

        String title = PublicPropertyDisclosurePolicy.safeCanonicalTitle(
                listing, locality, city);

        assertEquals("2 BHK Flat in Vijay Nagar, Indore", title);
        assertEquals("Vijay Nagar",
                PublicPropertyDisclosurePolicy.canonicalLocalityLabel(locality));
        assertEquals("Indore",
                PublicPropertyDisclosurePolicy.canonicalCityLabel(city, locality));
        assertNull(PublicPropertyDisclosurePolicy.safeBhk("Vijay Nagar 2 BHK"));
    }

    @Test
    void mediaRequiresTrustedCloudAccountAndGeneratedAssetProvenance() {
        String eligible = "https://res.cloudinary.com/pathome/image/upload/v1/"
                + "pathome/properties/images/media-12345678.webp";

        assertEquals(eligible, PublicPropertyDisclosurePolicy.safeOwnedMediaUrl(
                eligible, null, "media-12345678", "pathome"));
        assertEquals(eligible, PublicPropertyDisclosurePolicy.safeOwnedMediaUrl(
                eligible, "pathome/properties/images/media-12345678", null, "pathome"));
        assertNull(PublicPropertyDisclosurePolicy.safeOwnedMediaUrl(
                "https://res.cloudinary.com/foreign/image/upload/v1/"
                        + "pathome/properties/images/media-12345678.webp",
                null, "media-12345678", "pathome"));
        assertNull(PublicPropertyDisclosurePolicy.safeOwnedMediaUrl(
                "https://res.cloudinary.com/pathome/image/upload/v1/owner-9876543210.webp",
                null, null, "pathome"));
        assertNull(PublicPropertyDisclosurePolicy.safeOwnedMediaUrl(
                eligible, "pathome/properties/images/different", null, "pathome"));
    }
}
