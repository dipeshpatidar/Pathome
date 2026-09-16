package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class BatchPropertyPublishingService {

    private final ListingRepository listingRepository;
    private final PropertyMediaAssetRepository mediaAssetRepository;
    private final PropertyParserService propertyParserService;

    public BatchPropertyPublishingService(
            ListingRepository listingRepository,
            PropertyMediaAssetRepository mediaAssetRepository,
            PropertyParserService propertyParserService) {
        this.listingRepository = Objects.requireNonNull(listingRepository);
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository);
        this.propertyParserService = Objects.requireNonNull(propertyParserService);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Listing publish(RentalDetails listing, List<String> mediaUrls) {
        Objects.requireNonNull(listing, "Property listing must not be null");
        List<String> safeMediaUrls = mediaUrls == null
                ? List.of()
                : mediaUrls.stream().filter(Objects::nonNull).filter(url -> !url.isBlank()).toList();
        if (!safeMediaUrls.isEmpty()) {
            listing.setMediaGalleryUrls(String.join(",", safeMediaUrls));
        }

        Listing saved = listingRepository.saveAndFlush(listing);
        propertyParserService.confirmLocality(
                saved.getCity(), saved.getSector(), listing.getMonthlyRent().doubleValue());

        if (!safeMediaUrls.isEmpty()) {
            List<PropertyMediaAsset> assets = safeMediaUrls.stream().map(url -> {
                PropertyMediaAsset asset = new PropertyMediaAsset(
                        saved.getId(), url, MediaType.IMAGE, RoomTag.GENERAL, "Property photo");
                asset.setSector(saved.getSector());
                asset.setCity(saved.getCity());
                asset.setPriceTag(monthlyRentPriceTag(listing));
                asset.setVastuFacing(saved.getVastuFacing());
                return asset;
            }).toList();
            mediaAssetRepository.saveAllAndFlush(assets);
        }
        return saved;
    }

    private String monthlyRentPriceTag(RentalDetails listing) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        formatter.setMaximumFractionDigits(0);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(listing.getMonthlyRent()) + "/month";
    }
}
