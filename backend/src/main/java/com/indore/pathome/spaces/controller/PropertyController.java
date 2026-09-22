package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.AvailabilityStatus;
import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.exception.MediaUploadException;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.BatchPropertyPublishingService;
import com.indore.pathome.spaces.service.FailedUploadService;
import com.indore.pathome.spaces.service.MediaStagingService;
import com.indore.pathome.spaces.service.ParserLearningCaptureService;
import com.indore.pathome.spaces.service.ParserLearningService;
import com.indore.pathome.spaces.service.PropertyParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Controller providing RESTful endpoints for property listings, media assets, and AI natural language parsing.
 */
@RestController
@RequestMapping("/api/v1/properties")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PropertyController {

    private static final Logger log = LoggerFactory.getLogger(PropertyController.class);

    private static final java.util.regex.Pattern NUMERIC_DEPOSIT_PATTERN = java.util.regex.Pattern.compile("(\\d{4,6})");
    private static final java.util.regex.Pattern MONTHS_DEPOSIT_PATTERN = java.util.regex.Pattern.compile("(\\d+)\\s*(?:month|mahina)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern ONE_PLUS_ONE_PATTERN = java.util.regex.Pattern.compile("\\b([1-3])\\s*\\+\\s*([1-3])\\b");
    private static final java.util.regex.Pattern NUMERIC_VALUE_PATTERN = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final java.util.regex.Pattern BATHROOM_COUNT_PATTERN = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\b");
    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern.compile("^\\+91\\s?[6-9]\\d{4}[\\s-]?\\d{5}$");
    private static final java.util.regex.Pattern UPLOAD_REQUEST_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,80}$");
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DISPLAY_POSSESSION_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final Set<String> VALID_PREFERRED_TENANTS = Set.of(
            "FAMILY", "WORKING_PROFESSIONALS", "BACHELORS", "STUDENTS", "ANY"
    );

    private final ListingRepository listingRepository;
    private final PropertyMediaAssetRepository mediaAssetRepository;
    private final CloudinaryService cloudinaryService;
    private final FailedUploadService failedUploadService;
    private final PropertyParserService propertyParserService;
    private final ParserLearningService parserLearningService;
    private final ParserLearningCaptureService parserLearningCaptureService;
    private final BatchPropertyPublishingService batchPropertyPublishingService;
    private final MediaStagingService mediaStagingService;

    @Autowired(required = false)
    private PropertyUploadDraftRepository draftRepository;

    @Autowired(required = false)
    private com.indore.pathome.spaces.service.PropertyDraftService propertyDraftService;

    @Autowired(required = false)
    private com.indore.pathome.spaces.service.MediaUploadClaimService mediaUploadClaimService;

    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> activePublishingDrafts = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");

    @Autowired
    public PropertyController(
            ListingRepository listingRepository,
            PropertyMediaAssetRepository mediaAssetRepository,
            CloudinaryService cloudinaryService,
            FailedUploadService failedUploadService,
            PropertyParserService propertyParserService,
            ParserLearningService parserLearningService,
            ParserLearningCaptureService parserLearningCaptureService,
            BatchPropertyPublishingService batchPropertyPublishingService,
            @Qualifier("mediaStagingService") MediaStagingService mediaStagingService) {
        this.listingRepository = Objects.requireNonNull(listingRepository, "ListingRepository must not be null");
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository, "PropertyMediaAssetRepository must not be null");
        this.cloudinaryService = Objects.requireNonNull(cloudinaryService, "CloudinaryService must not be null");
        this.failedUploadService = Objects.requireNonNull(failedUploadService, "FailedUploadService must not be null");
        this.propertyParserService = Objects.requireNonNull(propertyParserService, "PropertyParserService must not be null");
        this.parserLearningService = Objects.requireNonNull(parserLearningService, "ParserLearningService must not be null");
        this.parserLearningCaptureService = Objects.requireNonNull(
                parserLearningCaptureService, "ParserLearningCaptureService must not be null");
        this.batchPropertyPublishingService = Objects.requireNonNull(
                batchPropertyPublishingService, "BatchPropertyPublishingService must not be null");
        this.mediaStagingService = mediaStagingService;
    }

    public PropertyController(
            ListingRepository listingRepository,
            PropertyMediaAssetRepository mediaAssetRepository,
            CloudinaryService cloudinaryService,
            FailedUploadService failedUploadService,
            PropertyParserService propertyParserService,
            ParserLearningService parserLearningService,
            ParserLearningCaptureService parserLearningCaptureService,
            BatchPropertyPublishingService batchPropertyPublishingService) {
        this(listingRepository, mediaAssetRepository, cloudinaryService, failedUploadService,
                propertyParserService, parserLearningService, parserLearningCaptureService,
                batchPropertyPublishingService, failedUploadService.getMediaStagingService());
    }

    public void setDraftRepository(PropertyUploadDraftRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    public void setPropertyDraftService(com.indore.pathome.spaces.service.PropertyDraftService propertyDraftService) {
        this.propertyDraftService = propertyDraftService;
    }

    public void setMediaUploadClaimService(com.indore.pathome.spaces.service.MediaUploadClaimService mediaUploadClaimService) {
        this.mediaUploadClaimService = mediaUploadClaimService;
    }


    /**
     * GET /api/v1/properties - Fetch active property listings.
     */
    @GetMapping
    public ResponseEntity<List<Listing>> getAllActiveProperties(
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String city) {

        List<Listing> listings = (sector != null && !sector.isBlank())
                ? listingRepository.findBySectorIgnoreCase(sector.trim())
                : listingRepository.findByStatus(ListingStatus.ACTIVE);

        return ResponseEntity.ok(listings);
    }

    /**
     * GET /api/v1/properties/{id} - Fetch single property details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPropertyById(@PathVariable Long id) {
        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Property listing not found");
        }
        return ResponseEntity.ok(listing);
    }

    /**
     * GET /api/v1/properties/{id}/tagged-media - Fetch rich tagged media assets.
     */
    @GetMapping("/{id}/tagged-media")
    public ResponseEntity<List<PropertyMediaAsset>> getTaggedMediaAssets(@PathVariable Long id) {
        return ResponseEntity.ok(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(id));
    }

    /**
     * POST /api/v1/properties/{id}/tagged-media - Upload single photo/video with metadata.
     *
     * <p>On success: if a prior failure record exists for the same {@code uploadRequestId},
     * it is automatically resolved.
     * On failure: the failure is persisted in {@code media_upload_failures} before propagating,
     * so the admin panel can display and retry it even after a page reload.</p>
     */
    @PostMapping("/{id}/tagged-media")
    public ResponseEntity<?> uploadTaggedMediaAsset(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "roomTag", defaultValue = "GENERAL") String roomTagStr,
            @RequestParam(value = "mediaType", defaultValue = "IMAGE") String mediaTypeStr,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "isPrimaryCover", defaultValue = "false") Boolean isPrimaryCover,
            @RequestParam(value = "sector", required = false) String sector,
            @RequestParam(value = "priceTag", required = false) String priceTag,
            @RequestParam(value = "vastuFacing", required = false) String vastuFacing,
            @RequestParam(value = "uploadRequestId", required = false) String uploadRequestId) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file cannot be null or empty");
        }

        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Property listing not found");
        }
        RoomTag roomTag = parseRoomTag(roomTagStr);
        MediaType mediaType = parseMediaType(mediaTypeStr);
        String normalizedUploadRequestId = normalizeUploadRequestId(uploadRequestId);

        // Idempotency: return existing asset if already uploaded successfully (Permanent Authority)
        if (normalizedUploadRequestId != null) {
            Optional<PropertyMediaAsset> existingAsset = mediaAssetRepository
                    .findByListingIdAndUploadRequestId(id, normalizedUploadRequestId);
            if (existingAsset.isPresent()) {
                // Also resolve any lingering failure record for this idempotency key
                failedUploadService.findFailureIdByUploadRequestId(normalizedUploadRequestId)
                        .ifPresent(fid -> failedUploadService.recordResolution(fid, existingAsset.get().getMediaUrl()));
                return ResponseEntity.ok(existingAsset.get());
            }
        }

        com.indore.pathome.spaces.service.MediaUploadClaimService.ClaimContext claimContext = null;
        if (normalizedUploadRequestId != null && mediaUploadClaimService != null) {
            com.indore.pathome.spaces.service.MediaUploadClaimService.ClaimResult claimResult =
                    mediaUploadClaimService.acquireOrWait(id, normalizedUploadRequestId);
            if (claimResult.isAlreadyComplete()) {
                PropertyMediaAsset asset = claimResult.getExistingAsset();
                failedUploadService.findFailureIdByUploadRequestId(normalizedUploadRequestId)
                        .ifPresent(fid -> failedUploadService.recordResolution(fid, asset.getMediaUrl()));
                return ResponseEntity.ok(asset);
            }
            if (claimResult.isConflict()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "timestamp", java.time.Instant.now().toString(),
                        "status", HttpStatus.CONFLICT.value(),
                        "error", "UPLOAD_IN_PROGRESS",
                        "message", "Media upload is already in progress for this item",
                        "path", "/api/v1/properties/" + id + "/tagged-media"
                ));
            }
            claimContext = claimResult.getClaimContext();
            mediaUploadClaimService.startHeartbeat(claimContext);
        }

        CloudinaryService.CloudinaryUploadResult uploadResult = null;
        try {
            // Check if prior DB_PERSIST failure already has Cloudinary storageUrl
            if (normalizedUploadRequestId != null) {
                Optional<com.indore.pathome.spaces.entity.MediaUploadFailure> priorFailure =
                        failedUploadService.findFailureByUploadRequestId(normalizedUploadRequestId);
                if (priorFailure.isPresent() && priorFailure.get().getStorageUrl() != null && !priorFailure.get().getStorageUrl().isBlank()) {
                    String sUrl = priorFailure.get().getStorageUrl();
                    String pId = priorFailure.get().getStoragePublicId() != null ? priorFailure.get().getStoragePublicId() : normalizedUploadRequestId;
                    uploadResult = new CloudinaryService.CloudinaryUploadResult(
                            sUrl, pId, mediaType == MediaType.VIDEO_WALKTHROUGH ? "video" : "image");
                    log.info("Reusing existing storageUrl from prior DB_PERSIST failure for uploadRequestId={}", normalizedUploadRequestId);
                }
            }

            if (uploadResult == null) {
                if (claimContext != null && claimContext.isReclaimedFromExpired()) {
                    // Reclaimed from expired lease (crash recovery): check Cloudinary deterministic publicId before uploading
                    boolean isVideo = mediaType == MediaType.VIDEO_WALKTHROUGH;
                    Optional<CloudinaryService.CloudinaryUploadResult> existingCdn =
                            cloudinaryService.findExistingResourceByUploadRequestId(normalizedUploadRequestId, isVideo);
                    if (existingCdn.isPresent()) {
                        uploadResult = existingCdn.get();
                    } else {
                        uploadResult = uploadMediaToCloudinaryResult(file, mediaType, normalizedUploadRequestId);
                    }
                } else {
                    // Normal upload: DO NOT call Admin API reconciliation
                    uploadResult = uploadMediaToCloudinaryResult(file, mediaType, normalizedUploadRequestId);
                }
            }
        } catch (MediaUploadException mue) {
            if (claimContext != null && mediaUploadClaimService != null) {
                mediaUploadClaimService.markFailed(claimContext, mue.getSafeReason());
            }
            String stagedKey = null;
            java.time.LocalDateTime stagingExpiresAt = null;

            boolean stageEligible = (mue.getCategory() == null || mue.getCategory().isStagingEligible())
                    && mue.getStage() != MediaUploadException.Stage.VALIDATION;

            if (stageEligible && mediaStagingService != null) {
                try {
                    String sanitizedFilename = sanitizeFilename(file.getOriginalFilename());
                    String key = "staging/" + (normalizedUploadRequestId != null ? normalizedUploadRequestId : UUID.randomUUID().toString()) + "/" + sanitizedFilename;
                    stagedKey = mediaStagingService.stage(key, file.getInputStream(), file.getSize(), file.getContentType());
                    stagingExpiresAt = java.time.LocalDateTime.now().plusDays(7);
                } catch (Exception stagingEx) {
                    log.warn("Failed to stage media binary after upload failure (non-critical): {}", stagingEx.getMessage());
                }
            }

            failedUploadService.recordFailure(new FailedUploadService.UploadFailureContext(
                    id,
                    normalizedUploadRequestId,
                    mediaType.name(),
                    file.getOriginalFilename() != null ? file.getOriginalFilename() : "",
                    file.getSize(),
                    roomTag.name(),
                    mue.getStage(),
                    mue.getSafeReason(),
                    mue.getDiagnostic(),
                    null,
                    null,
                    stagedKey,
                    stagingExpiresAt,
                    mue.getCategory() != null ? mue.getCategory().name() : null,
                    mue.getProviderStatusCode()
            ));
            throw mue;
        } finally {
            if (claimContext != null && mediaUploadClaimService != null) {
                mediaUploadClaimService.stopHeartbeat(claimContext);
            }
        }

        String cdnUrl = uploadResult.secureUrl();
        String publicId = uploadResult.publicId();

        try {
            PropertyMediaAsset savedAsset;
            try {
                savedAsset = saveMediaAsset(
                        id, cdnUrl, mediaType, roomTag, caption, isPrimaryCover,
                        sector, priceTag, vastuFacing, listing, normalizedUploadRequestId);
                updateListingGallery(listing, cdnUrl);
            } catch (Exception dbEx) {
                // Check if asset was already saved by concurrent request race
                Optional<PropertyMediaAsset> raceAsset = (normalizedUploadRequestId != null)
                        ? mediaAssetRepository.findByListingIdAndUploadRequestId(id, normalizedUploadRequestId)
                        : Optional.empty();
                if (raceAsset.isPresent()) {
                    savedAsset = raceAsset.get();
                } else {
                    throw dbEx;
                }
            }

            if (claimContext != null && mediaUploadClaimService != null) {
                mediaUploadClaimService.markCompleted(claimContext);
            }

            // Resolve any prior failure record for this idempotency key
            if (normalizedUploadRequestId != null) {
                failedUploadService.findFailureIdByUploadRequestId(normalizedUploadRequestId)
                        .ifPresent(fid -> failedUploadService.recordResolution(fid, cdnUrl));
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(savedAsset);
        } catch (Exception dbEx) {
            if (claimContext != null && mediaUploadClaimService != null) {
                mediaUploadClaimService.markFailed(claimContext, "Database persistence failed");
            }
            MediaUploadException mue = new MediaUploadException(
                    MediaUploadException.Stage.DB_PERSIST,
                    "Media was uploaded to storage, but could not be saved to the property. Please retry from Failed Uploads.",
                    MediaUploadException.diagnosticFrom(dbEx),
                    cdnUrl,
                    publicId,
                    dbEx
            );
            failedUploadService.recordFailure(new FailedUploadService.UploadFailureContext(
                    id,
                    normalizedUploadRequestId,
                    mediaType.name(),
                    file.getOriginalFilename() != null ? file.getOriginalFilename() : "",
                    file.getSize(),
                    roomTag.name(),
                    mue.getStage(),
                    mue.getSafeReason(),
                    mue.getDiagnostic(),
                    cdnUrl,
                    publicId,
                    null,
                    null,
                    null,
                    null
            ));
            throw mue;
        }

    }

    /**
     * POST /api/v1/properties - Admin Endpoint to create a new property listing.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Listing> createProperty(@RequestBody Map<String, Object> body) {
        Objects.requireNonNull(body, "Property payload must not be null");

        String ownerPhone = readString(body, "ownerPhoneNumber");
        if (isMissingValue(ownerPhone)) {
            ownerPhone = readString(body, "ownerPhone");
        }
        String sector = readString(body, "sector");
        Object rentObj = body.getOrDefault("monthlyRent", body.get("price"));
        String bhkCount = readString(body, "bhkCount");
        if (isMissingValue(bhkCount)) {
            bhkCount = readString(body, "bhk");
        }
        String propertyType = readString(body, "propertyType");
        if (isMissingValue(propertyType)) {
            propertyType = readString(body, "type");
        }

        validateRequiredFields(ownerPhone, sector, rentObj, bhkCount);
        if (isMissingValue(propertyType)) {
            throw new IllegalArgumentException("Property Creation Rejected: Property Type must be provided");
        }
        String city = readString(body, "city");
        if (isMissingValue(city)) {
            throw new IllegalArgumentException("Property Creation Rejected: City must be provided");
        }
        Double rentAmount = readDouble(rentObj);
        Double securityDeposit = readDouble(body.get("securityDeposit"));
        if (securityDeposit == null || securityDeposit <= 0) {
            throw new IllegalArgumentException("Property Creation Rejected: Security Deposit must be provided and greater than 0");
        }

        RentalDetails rental = new RentalDetails();
        rental.setTitle(!isMissingValue(readString(body, "title"))
                ? readString(body, "title") : bhkCount + " " + propertyType + " in " + sector);
        rental.setDescription(emptyToNull(readString(body, "description")));
        rental.setAddress(!isMissingValue(readString(body, "address")) ? readString(body, "address") : sector);
        rental.setSector(sector);
        rental.setCity(city.trim());
        rental.setBhkCount(bhkCount);
        rental.setFurnishingStatus(emptyToNull(readString(body, "furnishingStatus")));
        rental.setVastuFacing(emptyToNull(readString(body, "vastuFacing")));
        if (body.containsKey("amenities")) {
            Object am = body.get("amenities");
            rental.setAmenities(am instanceof List<?> list
                    ? String.join(", ", list.stream().filter(Objects::nonNull).map(Object::toString).toList())
                    : am != null ? am.toString() : null);
        }
        rental.setOwnerPhoneNumber(ownerPhone.trim());
        rental.setOwnerName(emptyToNull(readString(body, "ownerName")));
        String possessionDateText = emptyToNull(readString(body, "possessionDate"));
        rental.setPossessionDateText(possessionDateText);
        LocalDate availableFrom = readLocalDate(body.get("availableFrom"));
        if (availableFrom == null
                && AvailabilityStatus.fromExternalValue(readString(body, "availabilityStatus"))
                        == AvailabilityStatus.READY_NOW) {
            availableFrom = LocalDate.now(INDIA_ZONE);
        }
        if (availableFrom == null) availableFrom = parseDisplayPossessionDate(possessionDateText);
        rental.setAvailableFrom(availableFrom == null ? null : availableFrom.atStartOfDay());
        rental.setLatitude(readDouble(body.get("latitude")));
        rental.setLongitude(readDouble(body.get("longitude")));
        rental.setTotalAreaSqFt(readDouble(body.get("totalAreaSqFt")));
        rental.setMonthlyRent(BigDecimal.valueOf(rentAmount));
        rental.setSecurityDeposit(BigDecimal.valueOf(securityDeposit));
        rental.setStatus(toListingStatus(readString(body, "status")));
        rental.setPropertyType(toPropertyType(propertyType));

        Double floorVal = readDouble(body.get("floor"));
        if (floorVal == null) floorVal = readDouble(body.get("floorNumber"));
        if (floorVal != null) {
            if (floorVal < 0) throw new IllegalArgumentException("Property Creation Rejected: Floor must be 0 (Ground) or greater");
            rental.setFloorNumber(floorVal.intValue());
        }
        Double totalFloorsVal = readDouble(body.get("totalFloors"));
        if (totalFloorsVal != null) {
            if (totalFloorsVal < 1) throw new IllegalArgumentException("Property Creation Rejected: Total Floors must be at least 1");
            rental.setTotalFloors(totalFloorsVal.intValue());
        }
        PropertyType pt = toPropertyType(propertyType);
        boolean isLanded = pt == PropertyType.PLOT || pt == PropertyType.LAND || pt == PropertyType.HOUSE;
        if (!isLanded && rental.getFloorNumber() != null && rental.getTotalFloors() != null
                && rental.getFloorNumber() > rental.getTotalFloors()) {
            throw new IllegalArgumentException("Property Creation Rejected: Floor (" + rental.getFloorNumber()
                    + ") cannot exceed Total Floors (" + rental.getTotalFloors() + ")");
        }
        Object ptObj = body.get("preferredTenants");
        if (ptObj == null) ptObj = body.get("preferredTenant");
        if (ptObj instanceof List<?> list) {
            List<String> valid = list.stream().filter(Objects::nonNull).map(Object::toString).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).filter(VALID_PREFERRED_TENANTS::contains).toList();
            List<String> mutable = new ArrayList<>(valid);
            if (mutable.contains("ANY") && mutable.size() > 1) mutable.remove("ANY");
            if (!mutable.isEmpty()) rental.setPreferredTenant(String.join(",", mutable));
        } else if (ptObj != null && !ptObj.toString().isBlank()) {
            List<String> valid = Arrays.stream(ptObj.toString().split(",")).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).filter(VALID_PREFERRED_TENANTS::contains).toList();
            List<String> mutable = new ArrayList<>(valid);
            if (mutable.contains("ANY") && mutable.size() > 1) mutable.remove("ANY");
            if (!mutable.isEmpty()) rental.setPreferredTenant(String.join(",", mutable));
        }

        Listing saved = listingRepository.save(rental);
        propertyParserService.confirmLocality(rental.getCity(), sector, rentAmount);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * POST /api/v1/properties/{id}/photos - Admin Upload Property Photos to Cloudinary.
     */
    @PostMapping("/{id}/photos")
    public ResponseEntity<?> uploadPhotos(
            @PathVariable Long id,
            @RequestParam("files") MultipartFile[] files) {

        Optional<Listing> listingOpt = listingRepository.findById(id);
        if (listingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Listing not found");
        }

        Listing listing = listingOpt.get();
        List<String> uploadedUrls = processPhotoUploads(id, files, listing);
        updateListingGalleryList(listing, uploadedUrls);

        return ResponseEntity.ok(Map.of(
                "message", "Photos uploaded to Cloudinary successfully",
                "urls", uploadedUrls,
                "propertyId", id
        ));
    }

    /**
     * POST /api/v1/properties/{id}/video - Admin Upload Video Walkthrough to Cloudinary.
     */
    @PostMapping("/{id}/video")
    public ResponseEntity<?> uploadVideo(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {

        Optional<Listing> listingOpt = listingRepository.findById(id);
        if (listingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Listing not found");
        }

        Listing listing = listingOpt.get();
        String videoUrl = cloudinaryService.uploadVideo(file);

        PropertyMediaAsset asset = new PropertyMediaAsset(id, videoUrl, MediaType.VIDEO_WALKTHROUGH, RoomTag.GENERAL, "Video walkthrough");
        asset.setSector(listing.getSector());
        asset.setCity(listing.getCity());
        asset.setPriceTag(monthlyRentPriceTag(listing));
        asset.setVastuFacing(listing.getVastuFacing());
        mediaAssetRepository.save(asset);

        updateListingGallery(listing, videoUrl);

        return ResponseEntity.ok(Map.of(
                "message", "Video walkthrough uploaded to Cloudinary successfully",
                "videoUrl", videoUrl,
                "propertyId", id
        ));
    }

    /**
     * POST /api/v1/properties/parse-prompt - Read-only backend natural-language property parser.
     */
    @PostMapping("/parse-prompt")
    public ResponseEntity<ParsedPropertyDTO> parseNaturalLanguagePrompt(@RequestBody Map<String, String> request) {
        if (request == null || !request.containsKey("prompt")) {
            return ResponseEntity.badRequest().build();
        }
        ParsedPropertyDTO result = propertyParserService.parse(request.get("prompt"));
        quarantineParserResults(List.of(result), ParserInputSource.from(request.get("source")));
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/v1/properties/create-from-parsed - Persist verified ParsedPropertyDTO to PostgreSQL DB listings.
     */
    @PostMapping("/create-from-parsed")
    @Transactional
    public ResponseEntity<Map<String, Object>> createFromParsedPrompt(@RequestBody ParsedPropertyDTO dto) {
        Objects.requireNonNull(dto, "ParsedPropertyDTO must not be null");

        String draftId = dto.getDraftId() != null && !dto.getDraftId().isBlank() ? dto.getDraftId().trim() : null;

        // 1. Authoritative DB Idempotency Check: if a listing already exists for this origin draft, return it immediately
        if (draftId != null) {
            Optional<Listing> existingListing = listingRepository.findByOriginDraftId(draftId);
            if (existingListing.isPresent()) {
                Listing l = existingListing.get();
                log.info("Durable DB idempotency match: listing #{} already exists for origin draft [{}]. Replaying existing record.",
                        l.getId(), draftId);
                return ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "message", "Property was already successfully published from this draft",
                        "propertyId", l.getId(),
                        "parsedDto", dto
                ));
            }
        }

        // 2. Transactional Pessimistic Row Lock & Replay Check on Draft
        if (draftId != null && draftRepository != null) {
            Optional<PropertyUploadDraft> draftOpt = draftRepository.findByDraftIdForUpdate(draftId);
            if (draftOpt.isPresent()) {
                PropertyUploadDraft draft = draftOpt.get();
                if (draft.getPublishedPropertyId() != null) {
                    log.info("Durable draft idempotency: draft [{}] already linked to listing #{}. Returning existing record.",
                            draftId, draft.getPublishedPropertyId());
                    return ResponseEntity.ok(Map.of(
                            "status", "SUCCESS",
                            "message", "Property was already successfully published from this draft",
                            "propertyId", draft.getPublishedPropertyId(),
                            "parsedDto", dto
                    ));
                }

                // IDOR ownership check: ensure authenticated admin owns this draft
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getName() != null && !auth.getName().isBlank() && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
                    if (draft.getAdminId() != null && !draft.getAdminId().equalsIgnoreCase(auth.getName().trim())) {
                        throw new AccessDeniedException("You do not have permission to publish another administrator's draft.");
                    }
                }
            }
        }

        // 3. In-process double-click guard (fast non-blocking UI response for repeated clicks on same instance)
        boolean locked = false;
        if (draftId != null) {
            if (activePublishingDrafts.putIfAbsent(draftId, Boolean.TRUE) != null) {
                log.warn("Concurrent duplicate publication attempt blocked for draft [{}]", draftId);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "status", "CONFLICT",
                        "message", "Publication is already in progress for this draft. Please wait."
                ));
            }
            locked = true;
        }

        try {
            RentalDetails listing = buildRentalDetailsFromDTO(dto);
            Listing saved;
            try {
                saved = listingRepository.save(listing);
            } catch (DataIntegrityViolationException e) {
                // Hard DB unique constraint violated on origin_draft_id: another concurrent transaction committed first
                if (draftId != null) {
                    Optional<Listing> concurrentWinning = listingRepository.findByOriginDraftId(draftId);
                    if (concurrentWinning.isPresent()) {
                        log.info("Concurrent race resolved via DB unique constraint for draft [{}]. Returning listing #{}.",
                                draftId, concurrentWinning.get().getId());
                        return ResponseEntity.ok(Map.of(
                                "status", "SUCCESS",
                                "message", "Property was already successfully published from this draft",
                                "propertyId", concurrentWinning.get().getId(),
                                "parsedDto", dto
                        ));
                    }
                }
                throw e;
            }

            propertyParserService.confirmLocality(listing.getCity(), listing.getSector(), listing.getMonthlyRent().doubleValue());
            recordPublishedParserReview(dto, saved.getId());

            // 4. Link draft to the newly created listing with PUBLISHING status, retaining payload and staged media until media transfer completes safely
            if (draftId != null) {
                if (draftRepository != null) {
                    draftRepository.findByDraftId(draftId).ifPresent(draft -> {
                        draft.setPublishedPropertyId(saved.getId());
                        draft.setStatus("PUBLISHING");
                        draft.setUpdatedAt(LocalDateTime.now());
                        draftRepository.save(draft);
                        log.info("Linked draft [{}] to listing #{} with status PUBLISHING (retaining payload & staged media)", draftId, saved.getId());
                    });
                }
            }

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Property created and mapped to PostgreSQL listings & rental_details tables",
                    "propertyId", saved.getId(),
                    "parsedDto", dto
            ));
        } finally {
            if (locked) {
                activePublishingDrafts.remove(draftId);
            }
        }
    }

    /**
     * POST /api/v1/properties/parse-batch - High-Performance Multi-Prompt & Voice Batch Parser.
     */
    @PostMapping("/parse-batch")
    public ResponseEntity<List<ParsedPropertyDTO>> parseBatchPrompts(@RequestBody Map<String, String> request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        String prompt = request.getOrDefault("prompts", request.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<ParsedPropertyDTO> results = propertyParserService.parseBatch(prompt);
        quarantineParserResults(results, ParserInputSource.from(request.get("source")));
        return ResponseEntity.ok(results);
    }

    /**
     * POST /api/v1/properties/create-batch - Batch Persist Verified Listings to PostgreSQL.
     */
    @PostMapping("/create-batch")
    public ResponseEntity<Map<String, Object>> createBatchProperties(@RequestBody Map<String, Object> request) {
        Objects.requireNonNull(request, "Batch request must not be null");

        String topDraftId = readString(request, "draftId");
        List<?> rawListings = (List<?>) request.getOrDefault("listings", Collections.emptyList());
        List<Long> createdIds = new ArrayList<>();
        List<Map<String, Object>> createdListings = new ArrayList<>();
        List<Map<String, Object>> failedListings = new ArrayList<>();

        for (int i = 0; i < rawListings.size(); i++) {
            Object item = rawListings.get(i);
            try {
                ParsedPropertyDTO dto;
                if (item instanceof ParsedPropertyDTO p) {
                    dto = p;
                } else if (item instanceof Map<?, ?> m) {
                    dto = convertMapToParsedDTO((Map<String, Object>) m);
                } else {
                    continue;
                }

                String draftId = (dto.getDraftId() != null && !dto.getDraftId().isBlank())
                        ? dto.getDraftId().trim()
                        : topDraftId;
                if (dto.getDraftId() == null && draftId != null) {
                    dto.setDraftId(draftId);
                }
                String cardId = dto.getCardId();

                // Compute durable composite origin draft ID (e.g. draft_xxx:card_yyy)
                String compositeOriginDraftId = null;
                if (draftId != null && !draftId.isBlank()) {
                    if (cardId != null && !cardId.isBlank()) {
                        compositeOriginDraftId = draftId.trim() + ":" + cardId.trim();
                    } else {
                        compositeOriginDraftId = draftId.trim();
                    }
                    if (compositeOriginDraftId.length() > 64 && cardId != null) {
                        String hash = DigestUtils.md5DigestAsHex(cardId.trim().getBytes(StandardCharsets.UTF_8));
                        compositeOriginDraftId = draftId.trim() + ":" + hash;
                        if (compositeOriginDraftId.length() > 64) {
                            compositeOriginDraftId = compositeOriginDraftId.substring(0, 64);
                        }
                    }
                }

                // 1. Check idempotency: if this card already has a listing created, replay it
                if (compositeOriginDraftId != null) {
                    Optional<Listing> existing = listingRepository.findByOriginDraftId(compositeOriginDraftId);
                    if (existing.isPresent()) {
                        Listing replayed = existing.get();
                        log.info("Durable batch idempotency: card [{}] in draft [{}] already linked to listing #{}. Replaying existing record.",
                                cardId, draftId, replayed.getId());
                        createdIds.add(replayed.getId());
                        createdListings.add(Map.of(
                                "id", replayed.getId(),
                                "requestIndex", i,
                                "propertyNumber", i + 1,
                                "title", replayed.getTitle() != null ? replayed.getTitle() : "",
                                "sector", replayed.getSector() != null ? replayed.getSector() : "",
                                "status", "REPLAYED"
                        ));
                        continue;
                    }
                }

                RentalDetails listing = buildRentalDetailsFromDTO(dto);
                if (compositeOriginDraftId != null) {
                    listing.setOriginDraftId(compositeOriginDraftId);
                }

                Listing saved;
                try {
                    saved = batchPropertyPublishingService.publish(listing, dto.getMediaUrls());
                } catch (DataIntegrityViolationException e) {
                    // Concurrent duplicate publication race resolved via unique constraint on origin_draft_id
                    if (compositeOriginDraftId != null) {
                        Optional<Listing> concurrentWinning = listingRepository.findByOriginDraftId(compositeOriginDraftId);
                        if (concurrentWinning.isPresent()) {
                            Listing winning = concurrentWinning.get();
                            log.info("Concurrent batch race resolved via DB unique constraint for [{}]. Returning listing #{}.",
                                    compositeOriginDraftId, winning.getId());
                            createdIds.add(winning.getId());
                            createdListings.add(Map.of(
                                    "id", winning.getId(),
                                    "requestIndex", i,
                                    "propertyNumber", i + 1,
                                    "title", winning.getTitle() != null ? winning.getTitle() : "",
                                    "sector", winning.getSector() != null ? winning.getSector() : "",
                                    "status", "REPLAYED"
                            ));
                            continue;
                        }
                    }
                    throw e;
                }

                recordPublishedParserReview(dto, saved.getId());

                createdIds.add(saved.getId());
                createdListings.add(Map.of(
                        "id", saved.getId(),
                        "requestIndex", i,
                        "propertyNumber", i + 1,
                        "title", saved.getTitle() != null ? saved.getTitle() : "",
                        "sector", saved.getSector() != null ? saved.getSector() : "",
                        "status", "CREATED"
                ));
            } catch (Exception e) {
                log.error("Batch property {} could not be published", i + 1, e);
                failedListings.add(Map.of(
                        "index", i + 1,
                        "error", "Review this property's required details and try publishing it again."
                ));
            }
        }

        // Transition batch draft to PUBLISHING lifecycle
        String effectiveBatchDraftId = topDraftId;
        if (effectiveBatchDraftId == null && !rawListings.isEmpty()) {
            Object first = rawListings.get(0);
            if (first instanceof Map<?, ?> m) {
                effectiveBatchDraftId = readString((Map<String, Object>) m, "draftId");
            }
        }
        if (effectiveBatchDraftId != null && draftRepository != null && !createdIds.isEmpty()) {
            final String bDraftId = effectiveBatchDraftId;
            draftRepository.findByDraftId(bDraftId).ifPresent(draft -> {
                if (!"PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
                    draft.setStatus("PUBLISHING");
                    draft.setUpdatedAt(LocalDateTime.now());
                    draftRepository.save(draft);
                    log.info("Transitioned batch draft [{}] to PUBLISHING after publishing/replaying {} listings",
                            bDraftId, createdIds.size());
                }
            });
        }

        return ResponseEntity.ok(Map.of(
                "total", rawListings.size(),
                "successCount", createdIds.size(),
                "failedCount", failedListings.size(),
                "createdIds", createdIds,
                "createdListings", createdListings,
                "failedListings", failedListings
        ));
    }

    private ParsedPropertyDTO convertMapToParsedDTO(Map<String, Object> map) {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setRawPrompt(readString(map, "rawPrompt"));
        dto.setLearningExampleId(readString(map, "learningExampleId"));
        dto.setDraftId(readString(map, "draftId"));
        dto.setCardId(readString(map, "cardId"));
        Double promptIndex = readDouble(map.get("promptIndex"));
        dto.setPromptIndex(promptIndex == null ? 1 : Math.max(1, promptIndex.intValue()));
        dto.setTitle(readString(map, "title"));
        dto.setDescription(readString(map, "description"));
        dto.setBhk(readString(map, "bhk"));
        dto.setType(readString(map, "type"));
        dto.setStatus(readString(map, "status"));
        dto.setSector(readString(map, "sector"));
        dto.setCity(readString(map, "city"));
        dto.setColony(readString(map, "colony"));
        dto.setAddress(readString(map, "address"));
        dto.setState(readString(map, "state"));
        dto.setPincode(readString(map, "pincode"));
        dto.setLandmark(readString(map, "landmark"));
        dto.setOwnerName(readString(map, "ownerName"));
        dto.setOwnerPhone(readString(map, "ownerPhone"));
        dto.setFurnishingStatus(readString(map, "furnishingStatus"));
        dto.setVastuFacing(readString(map, "vastuFacing"));
        dto.setBathrooms(readString(map, "bathrooms"));
        dto.setAreaSqFt(readString(map, "areaSqFt"));
        dto.setRentVal(readString(map, "rentVal"));
        dto.setBrokerageVal(readString(map, "brokerageVal"));
        dto.setBrokerageDays(readString(map, "brokerageDays"));
        dto.setDepositVal(readString(map, "depositVal"));
        dto.setPossessionDate(readString(map, "possessionDate"));
        dto.setAvailabilityStatus(AvailabilityStatus.fromExternalValue(readString(map, "availabilityStatus")));
        dto.setAvailableFrom(readLocalDate(map.get("availableFrom")));
        dto.setAdminVerified(Boolean.TRUE.equals(map.get("adminVerified")));

        Double rentAmount = readDouble(map.get("rentAmount"));
        dto.setRentAmount(rentAmount);
        Object mediaObj = map.get("mediaUrls");
        if (mediaObj instanceof List<?> l) {
            List<String> urls = new ArrayList<>();
            for (Object o : l) {
                if (o != null) urls.add(o.toString());
            }
            dto.setMediaUrls(urls);
        }
        Object amenitiesObj = map.get("amenities");
        if (amenitiesObj instanceof List<?> l) {
            List<String> amenities = new ArrayList<>();
            for (Object amenity : l) {
                if (amenity != null && !amenity.toString().isBlank()) {
                    amenities.add(amenity.toString());
                }
            }
            dto.setAmenities(amenities);
        }

        Double floorVal = readDouble(map.get("floor"));
        if (floorVal == null) floorVal = readDouble(map.get("floorNumber"));
        dto.setFloor(floorVal == null ? null : floorVal.intValue());

        Double totalFloorsVal = readDouble(map.get("totalFloors"));
        dto.setTotalFloors(totalFloorsVal == null ? null : totalFloorsVal.intValue());

        Object tenantObj = map.get("preferredTenants");
        if (tenantObj == null) tenantObj = map.get("preferredTenant");
        if (tenantObj instanceof List<?> l) {
            List<String> tenants = new ArrayList<>();
            for (Object o : l) {
                if (o != null && !o.toString().isBlank()) {
                    tenants.add(o.toString().trim());
                }
            }
            if (tenants.contains("ANY") && tenants.size() > 1) {
                tenants.remove("ANY");
            }
            dto.setPreferredTenants(tenants);
        } else if (tenantObj instanceof String s && !s.isBlank()) {
            List<String> tenants = new ArrayList<>(Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(val -> !val.isBlank())
                    .toList());
            if (tenants.contains("ANY") && tenants.size() > 1) {
                tenants.remove("ANY");
            }
            dto.setPreferredTenants(tenants);
        }

        return dto;
    }

    private void quarantineParserResults(List<ParsedPropertyDTO> results, ParserInputSource inputSource) {
        try {
            parserLearningService.quarantinePredictions(results, inputSource);
        } catch (RuntimeException exception) {
            log.warn("Parser learning capture unavailable; parsing will continue safely: {}", exception.getMessage());
        }
    }

    private void recordPublishedParserReview(ParsedPropertyDTO dto, Long listingId) {
        parserLearningCaptureService.captureAfterSuccessfulPublish(dto, listingId);
    }

    private void validateRequiredFields(String ownerPhone, String sector, Object rentObj, String bhkCount) {
        List<String> missing = new ArrayList<>();

        if (isMissingValue(ownerPhone)) {
            missing.add("Owner Phone Number (Must not be null)");
        } else if (!PHONE_PATTERN.matcher(ownerPhone.trim()).matches()) {
            missing.add("Owner Phone Number (use a valid Indian number, e.g. +91 98260 12345)");
        }

        if (isMissingValue(sector)) {
            missing.add("Locality / Sector Name (Must not be null)");
        }

        Double rentVal = readDouble(rentObj);
        if (rentVal == null || rentVal <= 0) {
            missing.add("Monthly Rent Amount (Must be greater than 0)");
        }

        if (isMissingValue(bhkCount)) {
            missing.add("BHK Layout Count (Must not be null)");
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Property Creation Rejected: Missing or invalid required non-null fields: " + String.join(", ", missing));
        }
    }

    private RentalDetails buildRentalDetailsFromDTO(ParsedPropertyDTO dto) {
        if (!dto.isAdminVerified()) {
            throw new IllegalArgumentException("Property Creation Rejected: an administrator must review and confirm the parsed values before publishing");
        }
        validateRequiredFields(
                dto.getOwnerPhone(),
                dto.getSector(),
                dto.getRentAmount() != null ? dto.getRentAmount() : dto.getRentVal(),
                dto.getBhk()
        );
        if (isMissingValue(dto.getType())) {
            throw new IllegalArgumentException("Property Creation Rejected: Property Type must be confirmed before publishing");
        }
        if (isMissingValue(dto.getCity())) {
            throw new IllegalArgumentException("Property Creation Rejected: City must be confirmed before publishing");
        }

        RentalDetails listing = new RentalDetails();
        listing.setTitle(!isMissingValue(dto.getTitle()) ? dto.getTitle() : dto.getBhk() + " " + dto.getType() + " in " + dto.getSector());
        listing.setDescription(!isMissingValue(dto.getDescription()) ? dto.getDescription()
                : dto.getBhk() + " " + dto.getType() + " located in " + dto.getSector());
        listing.setSector(dto.getSector());
        listing.setAddress(!isMissingValue(dto.getAddress()) ? dto.getAddress() : dto.getSector() + ", " + dto.getCity());
        listing.setCity(dto.getCity().trim());
        listing.setBhkCount(dto.getBhk());
        listing.setOwnerName(emptyToNull(dto.getOwnerName()));
        listing.setBathroomCount(readInteger(dto.getBathrooms(), BATHROOM_COUNT_PATTERN));
        listing.setColony(emptyToNull(dto.getColony()));
        listing.setState(emptyToNull(dto.getState()));
        listing.setPincode(emptyToNull(dto.getPincode()));
        listing.setLandmark(emptyToNull(dto.getLandmark()));
        listing.setPossessionDateText(emptyToNull(dto.getPossessionDate()));
        LocalDate availableFrom = resolveAvailableFrom(dto);
        listing.setAvailableFrom(availableFrom == null ? null : availableFrom.atStartOfDay());
        listing.setFurnishingStatus(emptyToNull(dto.getFurnishingStatus()));
        listing.setVastuFacing(emptyToNull(dto.getVastuFacing()));
        if (dto.getAmenities() != null && !dto.getAmenities().isEmpty()) {
            listing.setAmenities(String.join(", ", dto.getAmenities()));
        }
        listing.setTotalAreaSqFt(readDouble(dto.getAreaSqFt()));
        listing.setMonthlyRent(BigDecimal.valueOf(dto.getRentAmount()));

        DepositTerms depositTerms = parseDeposit(dto.getDepositVal(), dto.getRentAmount());
        if (depositTerms.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Property Creation Rejected: Security Deposit must be explicitly confirmed before publishing");
        }
        listing.setSecurityDeposit(depositTerms.amount());
        listing.setSecurityDepositMonths(depositTerms.months());
        listing.setBrokerageAmount(toBigDecimal(dto.getBrokerageVal()));
        listing.setBrokerageDays(readInteger(dto.getBrokerageDays(), BATHROOM_COUNT_PATTERN));
        listing.setOwnerPhoneNumber(dto.getOwnerPhone().trim());
        listing.setStatus(toListingStatus(dto.getStatus()));
        listing.setPropertyType(toPropertyType(dto.getType()));

        PropertyType propType = toPropertyType(dto.getType());
        if (dto.getFloor() != null && dto.getFloor() < 0) {
            throw new IllegalArgumentException("Property Creation Rejected: Floor must be 0 (Ground) or greater");
        }
        if (dto.getTotalFloors() != null && dto.getTotalFloors() < 1) {
            throw new IllegalArgumentException("Property Creation Rejected: Total Floors must be at least 1");
        }
        boolean isLandedType = propType == PropertyType.PLOT || propType == PropertyType.LAND
                || propType == PropertyType.HOUSE;
        if (!isLandedType && dto.getFloor() != null && dto.getTotalFloors() != null
                && dto.getFloor() > dto.getTotalFloors()) {
            throw new IllegalArgumentException("Property Creation Rejected: Floor (" + dto.getFloor()
                    + ") cannot exceed Total Floors (" + dto.getTotalFloors() + ")");
        }

        listing.setFloorNumber(dto.getFloor());
        listing.setTotalFloors(dto.getTotalFloors());

        if (dto.getPreferredTenants() != null && !dto.getPreferredTenants().isEmpty()) {
            List<String> validTenants = new ArrayList<>();
            for (String t : dto.getPreferredTenants()) {
                String normalized = t.trim().toUpperCase(Locale.ROOT);
                if (VALID_PREFERRED_TENANTS.contains(normalized)) {
                    validTenants.add(normalized);
                }
            }
            if (validTenants.contains("ANY") && validTenants.size() > 1) {
                validTenants.remove("ANY");
            }
            if (!validTenants.isEmpty()) {
                listing.setPreferredTenant(String.join(",", validTenants));
            }
        }

        if (dto.getDraftId() != null && !dto.getDraftId().isBlank()) {
            listing.setOriginDraftId(dto.getDraftId().trim());
        }
        return listing;
    }

    private DepositTerms parseDeposit(String depositValue, Double rentAmount) {
        if (isMissingValue(depositValue)) {
            return new DepositTerms(BigDecimal.ZERO, null);
        }

        String normalized = depositValue.replace(",", "");
        java.util.regex.Matcher amountMatcher = NUMERIC_DEPOSIT_PATTERN.matcher(normalized);
        BigDecimal amount = amountMatcher.find() ? new BigDecimal(amountMatcher.group(1)) : BigDecimal.ZERO;
        Integer months = readInteger(normalized, MONTHS_DEPOSIT_PATTERN);
        java.util.regex.Matcher onePlusOneMatcher = ONE_PLUS_ONE_PATTERN.matcher(normalized);
        if (months == null && onePlusOneMatcher.find()) {
            months = Integer.parseInt(onePlusOneMatcher.group(1)) + Integer.parseInt(onePlusOneMatcher.group(2));
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0 && months != null && rentAmount != null && rentAmount > 0) {
            amount = BigDecimal.valueOf(rentAmount).multiply(BigDecimal.valueOf(months));
        }
        return new DepositTerms(amount, months);
    }

    private PropertyType toPropertyType(String type) {
        if (type == null) return PropertyType.FLAT;
        String normalized = type.toUpperCase(Locale.ROOT);
        if (normalized.contains("PENTHOUSE")) return PropertyType.PENTHOUSE;
        if (normalized.contains("STUDIO")) return PropertyType.STUDIO;
        if (normalized.contains("AIRBNB") || normalized.contains("SERVICED")) return PropertyType.SERVICED_APARTMENT;
        if (normalized.contains("PLOT")) return PropertyType.PLOT;
        if (normalized.contains("LAND")) return PropertyType.LAND;
        if (normalized.contains("HOUSE") || normalized.contains("VILLA") || normalized.contains("DUPLEX")) return PropertyType.HOUSE;
        return PropertyType.FLAT;
    }

    private ListingStatus toListingStatus(String status) {
        if (status == null) return ListingStatus.ACTIVE;
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PENDING" -> ListingStatus.PENDING;
            case "SOLD", "EXPIRED", "RENTED", "REMOVED", "CLOSED" -> ListingStatus.CLOSED;
            default -> ListingStatus.ACTIVE;
        };
    }

    private BigDecimal toBigDecimal(String value) {
        Double number = readDouble(value);
        return number == null ? null : BigDecimal.valueOf(number);
    }

    private Integer readInteger(String value, java.util.regex.Pattern pattern) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private Double readDouble(Object value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = NUMERIC_VALUE_PATTERN.matcher(value.toString().replace(",", ""));
        if (!matcher.find()) return null;
        try {
            return Double.valueOf(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LocalDate readLocalDate(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return LocalDate.parse(value.toString().trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Availability date must use the YYYY-MM-DD format");
        }
    }

    private LocalDate resolveAvailableFrom(ParsedPropertyDTO dto) {
        if (dto.getAvailableFrom() != null) return dto.getAvailableFrom();
        if (dto.getAvailabilityStatus() == AvailabilityStatus.READY_NOW) {
            return LocalDate.now(INDIA_ZONE);
        }
        return parseDisplayPossessionDate(dto.getPossessionDate());
    }

    private LocalDate parseDisplayPossessionDate(String possessionDate) {
        if (isMissingValue(possessionDate)) return null;
        try {
            return LocalDate.parse(possessionDate.trim(), DISPLAY_POSSESSION_DATE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String readString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private String emptyToNull(String value) {
        return isMissingValue(value) ? null : value.trim();
    }

    private boolean isMissingValue(String value) {
        return value == null || value.isBlank() || "Not Specified".equalsIgnoreCase(value)
                || "Unspecified".equalsIgnoreCase(value) || "UNSPECIFIED".equalsIgnoreCase(value);
    }

    private record DepositTerms(BigDecimal amount, Integer months) {
    }

    private RoomTag parseRoomTag(String roomTagStr) {
        try {
            return RoomTag.valueOf(roomTagStr.toUpperCase());
        } catch (Exception e) {
            return RoomTag.GENERAL;
        }
    }

    private MediaType parseMediaType(String mediaTypeStr) {
        try {
            return MediaType.valueOf(mediaTypeStr.toUpperCase());
        } catch (Exception e) {
            return MediaType.IMAGE;
        }
    }

    private String uploadMediaToCloudinary(MultipartFile file, MediaType mediaType) {
        return uploadMediaToCloudinary(file, mediaType, null);
    }

    private String uploadMediaToCloudinary(
            MultipartFile file,
            MediaType mediaType,
            String uploadRequestId) {
        return uploadMediaToCloudinaryResult(file, mediaType, uploadRequestId).secureUrl();
    }

    private CloudinaryService.CloudinaryUploadResult uploadMediaToCloudinaryResult(
            MultipartFile file,
            MediaType mediaType,
            String uploadRequestId) {
        if (mediaType == MediaType.VIDEO_WALKTHROUGH) {
            CloudinaryService.CloudinaryUploadResult res = cloudinaryService.uploadVideoResult(file, uploadRequestId);
            if (res != null) return res;
            String url = cloudinaryService.uploadVideo(file, uploadRequestId);
            return new CloudinaryService.CloudinaryUploadResult(url, uploadRequestId, "video");
        } else {
            CloudinaryService.CloudinaryUploadResult res = cloudinaryService.uploadImageResult(file, uploadRequestId);
            if (res != null) return res;
            String url = cloudinaryService.uploadImage(file, uploadRequestId);
            return new CloudinaryService.CloudinaryUploadResult(url, uploadRequestId, "image");
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "media_file";
        String sanitized = SAFE_FILENAME_PATTERN.matcher(filename).replaceAll("_");
        return sanitized.length() > 80 ? sanitized.substring(sanitized.length() - 80) : sanitized;
    }

    private PropertyMediaAsset saveMediaAsset(
            Long id, String cdnUrl, MediaType mediaType, RoomTag roomTag,
            String caption, Boolean isPrimaryCover, String sector,
            String priceTag, String vastuFacing, Listing listing,
            String uploadRequestId) {

        PropertyMediaAsset asset = new PropertyMediaAsset();
        asset.setListingId(id);
        asset.setMediaUrl(cdnUrl);
        asset.setMediaType(mediaType);
        asset.setRoomTag(roomTag);
        asset.setCaption(caption != null && !caption.isBlank() ? caption.trim() : roomTag.getDisplayName());
        asset.setIsPrimaryCover(isPrimaryCover);
        asset.setSector(sector != null && !sector.isBlank() ? sector : (listing != null ? listing.getSector() : null));
        asset.setCity(listing != null ? listing.getCity() : null);
        asset.setPriceTag(priceTag != null && !priceTag.isBlank() ? priceTag : monthlyRentPriceTag(listing));
        asset.setVastuFacing(vastuFacing != null && !vastuFacing.isBlank() ? vastuFacing : (listing != null ? listing.getVastuFacing() : null));
        asset.setVerificationStatus("ADMIN_UPLOADED");
        asset.setUploadRequestId(uploadRequestId);

        return mediaAssetRepository.save(asset);
    }

    private String normalizeUploadRequestId(String uploadRequestId) {
        if (uploadRequestId == null || uploadRequestId.isBlank()) return null;
        String normalized = uploadRequestId.trim();
        if (!UPLOAD_REQUEST_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Media upload identifier is invalid");
        }
        return normalized;
    }

    private void updateListingGallery(Listing listing, String cdnUrl) {
        if (listing == null || cdnUrl == null || cdnUrl.isBlank()) return;
        String trimmedUrl = cdnUrl.trim();
        // Atomic Database Update (prevents lost updates under concurrency = 3)
        if (listing.getId() != null) {
            listingRepository.appendMediaGalleryUrlAtomic(listing.getId(), trimmedUrl);
        }
        // In-memory update for immediate callers/mocks without overwriting DB
        String existing = listing.getMediaGalleryUrls();
        if (existing == null || existing.isBlank()) {
            listing.setMediaGalleryUrls(trimmedUrl);
        } else {
            boolean alreadyPresent = false;
            for (String url : existing.split(",")) {
                if (url.trim().equals(trimmedUrl)) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) {
                listing.setMediaGalleryUrls(existing + "," + trimmedUrl);
            }
        }
    }

    private List<String> processPhotoUploads(Long id, MultipartFile[] files, Listing listing) {
        List<String> uploadedUrls = new ArrayList<>();
        for (MultipartFile file : files) {
            String cdnUrl = cloudinaryService.uploadImage(file);
            uploadedUrls.add(cdnUrl);

            PropertyMediaAsset asset = new PropertyMediaAsset(id, cdnUrl, MediaType.IMAGE, RoomTag.GENERAL, "Property photo");
            asset.setSector(listing.getSector());
            asset.setCity(listing.getCity());
            asset.setPriceTag(monthlyRentPriceTag(listing));
            asset.setVastuFacing(listing.getVastuFacing());
            mediaAssetRepository.save(asset);
        }
        return uploadedUrls;
    }

    private void updateListingGalleryList(Listing listing, List<String> uploadedUrls) {
        if (listing == null || uploadedUrls == null || uploadedUrls.isEmpty()) return;
        for (String url : uploadedUrls) {
            updateListingGallery(listing, url);
        }
    }

    private String monthlyRentPriceTag(Listing listing) {
        if (!(listing instanceof RentalDetails rental) || rental.getMonthlyRent() == null) {
            return null;
        }
        return "₹" + rental.getMonthlyRent().toPlainString() + " / month";
    }
}
