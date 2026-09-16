package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.ParserFieldReviewRepository;
import com.indore.pathome.spaces.repository.ParserTrainingExampleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ParserLearningService {

    static final String PARSER_VERSION = "rules-2026.09.16.1";
    private static final int MAX_PROMPT_LENGTH = 20_000;
    private static final int HOLDOUT_PERCENT = 15;
    private static final int VALIDATION_PERCENT = 15;
    private static final int QUARANTINE_RETENTION_DAYS = 30;
    private static final int REJECTED_RETENTION_DAYS = 90;
    private static final Set<String> TRAINABLE_FIELDS = Set.of(
            "bhk", "type", "city", "sector", "colony", "rentAmount", "brokerageVal",
            "brokerageDays", "areaSqFt", "depositVal", "bathrooms", "furnishingStatus",
            "possessionDate", "state", "pincode", "landmark", "vastuFacing");
    private static final Set<String> PRIVATE_FIELDS = Set.of("ownerName", "ownerPhone");
    private static final Pattern EMPTY_VALUE_PATTERN = Pattern.compile(
            "^(?:null|not specified|unspecified|unmentioned|unknown|n/?a)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITIVE_NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern BHK_VALUE_PATTERN = Pattern.compile(
            "^(?:[1-9](?:\\.5)?|10)\\s*(?:BHK|RK)$|^(?:DUPLEX|TRIPLEX)(?:\\s+VILLA)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PINCODE_PATTERN = Pattern.compile("^[1-9]\\d{5}$");
    private static final Pattern PHONE_LIKE_IN_TEXT_PATTERN = Pattern.compile(
            "(?<!\\d)\\+?(?:\\d[\\s-]?){8,13}(?!\\d)");
    private static final Pattern PHONE_AFTER_CUE_PATTERN = Pattern.compile(
            "\\b(?:owner\\s+(?:phone|number)|phone|mobile|contact|call)\\b[^\\d+]{0,20}" +
            "(\\+?(?:\\d[\\s().-]?){4,16})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_IN_TEXT_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_NAME_IN_TEXT_PATTERN = Pattern.compile(
            "\\b(?:owner(?:\\s+name)?|contact\\s+person)(?:\\s+(?:is|as))?\\s*[:\\-]?\\s*" +
            "([a-z][a-z .'-]{1,80}?)(?=\\s+(?:\\+?\\d|phone|mobile|contact)\\b|[,;&.\\n]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BHK_EVIDENCE_PATTERN = Pattern.compile(
            "\\b([1-9](?:\\.5)?|10)\\s*(?:bhk|rk)|\\b(?:bhk|rk)\\s*([1-9](?:\\.5)?|10)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BATHROOM_EVIDENCE_PATTERN = Pattern.compile(
            "\\b(1|2|3|4|5|6|7|8|9|10|one|two|three|four|five|six|seven|eight|nine|ten)\\s*" +
            "(?:bath|baths|bathroom|bathrooms|bathrom|bathromm|toilet|toilets|washroom|washrooms)\\b|" +
            "\\b(?:bath|baths|bathroom|bathrooms|bathrom|bathromm|toilet|toilets|washroom|washrooms)" +
            "(?:\\s+(?:is|are|has|having))?\\s*(1|2|3|4|5|6|7|8|9|10|one|two|three|four|five|six|seven|eight|nine|ten|to|too)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RENT_EVIDENCE_PATTERN = Pattern.compile(
            "\\b(?:monthly\\s+rent|rent|kiraya)\\b[^\\d]{0,30}(\\d{1,3}(?:,\\d{2,3})+|\\d{3,7}|\\d{1,3}k)\\b|" +
            "\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{3,7}|\\d{1,3}k)\\b[^\\d]{0,20}\\b(?:monthly\\s+rent|rent|kiraya)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BROKERAGE_EVIDENCE_PATTERN = Pattern.compile(
            "\\b(?:brokerage|brokrage|brookerage|commission|broker\\s+fee)\\b[^\\d]{0,30}(\\d{1,3}(?:,\\d{2,3})+|\\d{3,7}|\\d{1,3}k)\\b|" +
            "\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{3,7}|\\d{1,3}k)\\b[^\\d]{0,20}\\b(?:brokerage|brokrage|brookerage|commission|broker\\s+fee)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPOSIT_EVIDENCE_PATTERN = Pattern.compile(
            "\\b(?:security\\s+deposit|deposit|depost|deposite|securuity)\\b[^\\d]{0,30}(\\d{1,3}(?:,\\d{2,3})+|\\d{3,7}|\\d{1,3}k)\\b|" +
            "\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{3,7}|\\d{1,3}k)\\b[^\\d]{0,20}\\b(?:security\\s+deposit|deposit|depost|deposite|securuity)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ParserTrainingExampleRepository exampleRepository;
    private final ParserFieldReviewRepository fieldReviewRepository;
    private final ObjectMapper objectMapper;

    public ParserLearningService(
            ParserTrainingExampleRepository exampleRepository,
            ParserFieldReviewRepository fieldReviewRepository,
            ObjectMapper objectMapper) {
        this.exampleRepository = Objects.requireNonNull(exampleRepository);
        this.fieldReviewRepository = Objects.requireNonNull(fieldReviewRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public void quarantinePredictions(List<ParsedPropertyDTO> properties, ParserInputSource inputSource) {
        if (properties == null || properties.isEmpty()) return;

        String batchId = UUID.randomUUID().toString();
        List<ParserTrainingExample> examples = new ArrayList<>(properties.size());
        for (ParsedPropertyDTO property : properties) {
            ParserTrainingExample example = createQuarantinedExample(property, batchId, inputSource);
            property.setLearningExampleId(example.getId());
            examples.add(example);
        }
        exampleRepository.saveAll(examples);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPublishedReview(ParsedPropertyDTO reviewed, Long publishedListingId) {
        Objects.requireNonNull(reviewed, "Reviewed property must not be null");
        Objects.requireNonNull(publishedListingId, "Published listing id must not be null");

        ParserTrainingExample example = findOrCreateForPublishedReview(reviewed, publishedListingId);

        Map<String, String> initialValues = readSnapshot(example.getInitialPredictionJson());
        Map<String, String> reviewedValues = snapshot(reviewed);
        String rawPrompt = safePrompt(reviewed.getRawPrompt());

        fieldReviewRepository.deleteByExample_Id(example.getId());
        List<ParserFieldReview> reviews = buildFieldReviews(
                example, rawPrompt, initialValues, reviewedValues);
        fieldReviewRepository.saveAll(reviews);

        long eligibleCount = reviews.stream().filter(ParserFieldReview::isTrainingEligible).count();
        example.setFinalReviewedJson(writeSnapshot(reviewedValues));
        example.setPublishedListingId(publishedListingId);
        example.setReviewedBy(currentReviewer());
        example.setReviewStatus(ParserReviewStatus.PUBLISHED_PENDING_CURATION);
        example.setLabelCount(reviews.size());
        example.setEligibleLabelCount(Math.toIntExact(eligibleCount));
        example.setPublishedAt(LocalDateTime.now());
        example.setDatasetPartition(partitionForHash(example.getPromptHash()));
        example.setExclusionReason(eligibleCount == 0
                ? "No reviewed field has reliable source evidence"
                : null);
        exampleRepository.save(example);
    }

    @Transactional(readOnly = true)
    public Page<ParserLearningExampleDTO> pendingExamples(Pageable pageable) {
        Page<ParserTrainingExample> page = exampleRepository.findByReviewStatusOrderByCreatedAtAsc(
                ParserReviewStatus.PUBLISHED_PENDING_CURATION, pageable);
        Map<String, List<ParserFieldReview>> fieldsByExample = fieldsByExample(page.getContent());
        List<ParserLearningExampleDTO> content = page.getContent().stream()
                .map(example -> toExampleDto(
                        example, fieldsByExample.getOrDefault(example.getId(), List.of())))
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ParserLearningExampleDTO getExample(String id) {
        return toExampleDto(findExample(id));
    }

    @Transactional
    public ParserLearningExampleDTO approveForTraining(String id) {
        ParserTrainingExample example = findExample(id);
        if (example.getReviewStatus() != ParserReviewStatus.PUBLISHED_PENDING_CURATION) {
            throw new IllegalArgumentException("Only published examples awaiting curation can be approved");
        }
        if (example.getEligibleLabelCount() < 1) {
            throw new IllegalArgumentException("This example has no source-supported fields eligible for training");
        }

        ensureEligibleSpansDoNotOverlap(example.getId());

        List<ParserTrainingExample> duplicates = exampleRepository
                .findTop20ByPromptHashAndReviewStatusOrderByCreatedAtDesc(
                        example.getPromptHash(),
                        ParserReviewStatus.APPROVED_FOR_TRAINING);
        for (ParserTrainingExample duplicate : duplicates) {
            if (Objects.equals(duplicate.getFinalReviewedJson(), example.getFinalReviewedJson())) {
                throw new IllegalArgumentException("An identical reviewed example is already approved");
            }
            throw new IllegalArgumentException(
                    "Approval blocked because the same source prompt has conflicting reviewed values");
        }

        example.setReviewStatus(ParserReviewStatus.APPROVED_FOR_TRAINING);
        example.setCuratedAt(LocalDateTime.now());
        example.setReviewedBy(currentReviewer());
        example.setExclusionReason(null);
        exampleRepository.save(example);
        return toExampleDto(example);
    }

    @Transactional
    public ParserLearningExampleDTO rejectExample(String id, String reason) {
        ParserTrainingExample example = findExample(id);
        reject(example, reason == null || reason.isBlank()
                ? "Rejected during training-data curation"
                : reason.trim());
        return toExampleDto(example);
    }

    @Transactional(readOnly = true)
    public Page<ParserTrainingRecordDTO> approvedDataset(
            ParserDatasetPartition partition, Pageable pageable) {
        if (partition == ParserDatasetPartition.EXCLUDED) {
            throw new IllegalArgumentException("Excluded examples cannot be exported as training data");
        }
        Page<ParserTrainingExample> page = exampleRepository
                .findByReviewStatusAndDatasetPartitionOrderByCreatedAtAsc(
                        ParserReviewStatus.APPROVED_FOR_TRAINING, partition, pageable);
        Map<String, List<ParserFieldReview>> fieldsByExample = fieldsByExample(page.getContent());
        List<ParserTrainingRecordDTO> content = page.getContent().stream()
                .map(example -> toTrainingRecord(
                        example, fieldsByExample.getOrDefault(example.getId(), List.of())))
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ParserLearningStatsDTO stats() {
        return new ParserLearningStatsDTO(
                exampleRepository.countByReviewStatus(ParserReviewStatus.QUARANTINED),
                exampleRepository.countByReviewStatus(ParserReviewStatus.PUBLISHED_PENDING_CURATION),
                exampleRepository.countByReviewStatusAndDatasetPartition(
                        ParserReviewStatus.APPROVED_FOR_TRAINING, ParserDatasetPartition.TRAIN),
                exampleRepository.countByReviewStatusAndDatasetPartition(
                        ParserReviewStatus.APPROVED_FOR_TRAINING, ParserDatasetPartition.VALIDATION),
                exampleRepository.countByReviewStatusAndDatasetPartition(
                        ParserReviewStatus.APPROVED_FOR_TRAINING, ParserDatasetPartition.HOLDOUT),
                exampleRepository.countByReviewStatus(ParserReviewStatus.REJECTED));
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void removeExpiredUntrustedExamples() {
        LocalDateTime quarantineCutoff = LocalDateTime.now().minusDays(QUARANTINE_RETENTION_DAYS);
        LocalDateTime rejectedCutoff = LocalDateTime.now().minusDays(REJECTED_RETENTION_DAYS);
        fieldReviewRepository.deleteExpiredForExampleStatus(
                ParserReviewStatus.QUARANTINED, quarantineCutoff);
        exampleRepository.deleteExpiredByStatus(ParserReviewStatus.QUARANTINED, quarantineCutoff);
        fieldReviewRepository.deleteExpiredForExampleStatus(ParserReviewStatus.REJECTED, rejectedCutoff);
        exampleRepository.deleteExpiredByStatus(ParserReviewStatus.REJECTED, rejectedCutoff);
    }

    private ParserTrainingExample createQuarantinedExample(
            ParsedPropertyDTO property, String batchId, ParserInputSource inputSource) {
        String rawPrompt = safePrompt(property.getRawPrompt());
        Map<String, String> values = snapshot(property);

        ParserTrainingExample example = new ParserTrainingExample();
        example.setBatchId(batchId);
        example.setPropertyIndex(Math.max(1, property.getPromptIndex()));
        example.setInputSource(inputSource != null ? inputSource : ParserInputSource.UNKNOWN);
        example.setReviewStatus(ParserReviewStatus.QUARANTINED);
        example.setDatasetPartition(ParserDatasetPartition.EXCLUDED);
        example.setRawPrompt(rawPrompt);
        example.setPromptHash(hash(rawPrompt));
        example.setInitialPredictionJson(writeSnapshot(values));
        example.setParserVersion(PARSER_VERSION);
        example.setCreatedBy(currentReviewer());
        return example;
    }

    private ParserTrainingExample findOrCreateForPublishedReview(
            ParsedPropertyDTO reviewed, Long publishedListingId) {
        String exampleId = reviewed.getLearningExampleId();
        if (exampleId != null && !exampleId.isBlank()) {
            Optional<ParserTrainingExample> existing = exampleRepository.findById(exampleId);
            if (existing.isPresent()) {
                validateLearningExampleOwnership(existing.get(), reviewed, publishedListingId);
                return existing.get();
            }
        }

        ParserTrainingExample created = createQuarantinedExample(
                reviewed, UUID.randomUUID().toString(), ParserInputSource.UNKNOWN);
        reviewed.setLearningExampleId(created.getId());
        return exampleRepository.save(created);
    }

    private void validateLearningExampleOwnership(
            ParserTrainingExample example, ParsedPropertyDTO reviewed, Long publishedListingId) {
        if (example.getReviewStatus() != ParserReviewStatus.QUARANTINED) {
            throw new IllegalArgumentException("Parser learning example is no longer available for publishing");
        }
        if (!Objects.equals(example.getPromptHash(), hash(safePrompt(reviewed.getRawPrompt())))) {
            throw new IllegalArgumentException("Parser learning example does not match this property description");
        }
        if (example.getPropertyIndex() != Math.max(1, reviewed.getPromptIndex())) {
            throw new IllegalArgumentException("Parser learning example does not match this property position");
        }
        if (!Objects.equals(example.getCreatedBy(), currentReviewer())) {
            throw new IllegalArgumentException("Parser learning example belongs to a different administrator session");
        }
        if (example.getPublishedListingId() != null
                && !Objects.equals(example.getPublishedListingId(), publishedListingId)) {
            throw new IllegalArgumentException("Parser learning example is already linked to another property");
        }
    }

    private List<ParserFieldReview> buildFieldReviews(
            ParserTrainingExample example,
            String rawPrompt,
            Map<String, String> initialValues,
            Map<String, String> reviewedValues) {
        List<ParserFieldReview> reviews = new ArrayList<>();
        for (Map.Entry<String, String> field : reviewedValues.entrySet()) {
            String fieldName = field.getKey();
            String reviewedValue = field.getValue();
            if (isMissing(reviewedValue)) continue;

            String predictedValue = initialValues.get(fieldName);
            Evidence evidence = locateEvidence(rawPrompt, fieldName, reviewedValue);
            boolean valueValid = isSaneValue(fieldName, reviewedValue);
            boolean modelField = TRAINABLE_FIELDS.contains(fieldName);
            boolean privateField = PRIVATE_FIELDS.contains(fieldName);
            boolean eligible = modelField && valueValid && evidence != null && !privateField;

            ParserFieldReview review = new ParserFieldReview();
            review.setExample(example);
            review.setFieldName(fieldName);
            review.setPredictedValue(emptyToNull(predictedValue));
            review.setReviewedValue(reviewedValue);
            review.setCorrected(!equivalent(predictedValue, reviewedValue));
            review.setEvidenceSupported(evidence != null);
            review.setValueValid(valueValid);
            review.setTrainingEligible(eligible);
            if (evidence != null) {
                review.setSourceStart(evidence.start());
                review.setSourceEnd(evidence.end());
                review.setSourceText(rawPrompt.substring(evidence.start(), evidence.end()));
            }
            review.setExclusionReason(exclusionReason(modelField, privateField, valueValid, evidence));
            reviews.add(review);
        }
        return reviews;
    }

    private String exclusionReason(
            boolean modelField, boolean privateField, boolean valueValid, Evidence evidence) {
        if (privateField) return "Personal data is retained for audit only";
        if (!modelField) return "Field is deterministic or generated and is not learned";
        if (!valueValid) return "Reviewed value failed the field safety rules";
        if (evidence == null) return "Reviewed value is not supported by the original property text";
        return null;
    }

    private Evidence locateEvidence(String text, String fieldName, String value) {
        if (text == null || text.isBlank() || isMissing(value)) return null;

        if ("rentAmount".equals(fieldName)) {
            return findAmountEvidence(text, value, RENT_EVIDENCE_PATTERN);
        }
        if ("brokerageVal".equals(fieldName)) {
            return findAmountEvidence(text, value, BROKERAGE_EVIDENCE_PATTERN);
        }
        if ("depositVal".equals(fieldName)) {
            return findAmountEvidence(text, value, DEPOSIT_EVIDENCE_PATTERN);
        }
        if ("bhk".equals(fieldName)) {
            return findBhkEvidence(text, value);
        }
        if ("bathrooms".equals(fieldName)) {
            return findBathroomEvidence(text, value);
        }
        return findCanonicalEvidence(text, value);
    }

    private Evidence findAmountEvidence(String text, String value, Pattern pattern) {
        Long expected = normalizedAmount(value);
        if (expected == null) return null;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) == null) continue;
                Long found = normalizedAmount(matcher.group(group));
                if (Objects.equals(expected, found)) {
                    return new Evidence(matcher.start(group), matcher.end(group));
                }
            }
        }
        return null;
    }

    private Evidence findBhkEvidence(String text, String value) {
        String expected = firstNumber(value);
        if (expected == null) return findCanonicalEvidence(text, value);
        Matcher matcher = BHK_EVIDENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String found = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (expected.equals(found)) return new Evidence(matcher.start(), matcher.end());
        }
        return null;
    }

    private Evidence findBathroomEvidence(String text, String value) {
        String expected = firstNumber(value);
        if (expected == null) return null;
        Matcher matcher = BATHROOM_EVIDENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (expected.equals(numberWord(token))) return new Evidence(matcher.start(), matcher.end());
        }
        return null;
    }

    private Evidence findCanonicalEvidence(String text, String value) {
        CanonicalText canonicalText = canonicalizeWithOffsets(text);
        String canonicalValue = canonical(value);
        if (canonicalValue.isBlank()) return null;
        int canonicalStart = canonicalText.value().indexOf(canonicalValue);
        if (canonicalStart < 0) return null;
        int canonicalEnd = canonicalStart + canonicalValue.length() - 1;
        return new Evidence(
                canonicalText.originalIndexes().get(canonicalStart),
                canonicalText.originalIndexes().get(canonicalEnd) + 1);
    }

    private boolean isSaneValue(String field, String value) {
        if (isMissing(value) || value.length() > 500) return false;
        return switch (field) {
            case "bhk" -> BHK_VALUE_PATTERN.matcher(value.trim()).matches();
            case "rentAmount", "brokerageVal", "depositVal", "areaSqFt", "brokerageDays", "bathrooms" -> {
                Matcher matcher = POSITIVE_NUMBER_PATTERN.matcher(value.replace(",", ""));
                yield matcher.find() && Double.parseDouble(matcher.group()) > 0;
            }
            case "pincode" -> PINCODE_PATTERN.matcher(value.trim()).matches();
            default -> true;
        };
    }

    private ParserLearningExampleDTO toExampleDto(ParserTrainingExample example) {
        return toExampleDto(example, fieldReviewRepository
                .findByExample_IdOrderByFieldNameAsc(example.getId()));
    }

    private ParserLearningExampleDTO toExampleDto(
            ParserTrainingExample example, List<ParserFieldReview> fieldReviews) {
        List<ParserFieldReviewDTO> fields = fieldReviews.stream()
                .map(field -> new ParserFieldReviewDTO(
                        field.getFieldName(), field.getPredictedValue(), field.getReviewedValue(),
                        field.getSourceText(), field.getSourceStart(), field.getSourceEnd(),
                        field.isCorrected(), field.isEvidenceSupported(), field.isValueValid(),
                        field.isTrainingEligible(), field.getExclusionReason()))
                .toList();
        return new ParserLearningExampleDTO(
                example.getId(), example.getBatchId(), example.getPropertyIndex(),
                example.getInputSource(), example.getReviewStatus(), example.getDatasetPartition(),
                example.getRawPrompt(), example.getParserVersion(), example.getPublishedListingId(),
                example.getReviewedBy(), example.getExclusionReason(), example.getLabelCount(),
                example.getEligibleLabelCount(), example.getCreatedAt(), example.getPublishedAt(),
                example.getCuratedAt(), fields);
    }

    private Map<String, List<ParserFieldReview>> fieldsByExample(List<ParserTrainingExample> examples) {
        if (examples.isEmpty()) return Collections.emptyMap();
        List<String> ids = examples.stream().map(ParserTrainingExample::getId).toList();
        Map<String, List<ParserFieldReview>> grouped = new HashMap<>();
        for (ParserFieldReview field : fieldReviewRepository.findForExamples(ids)) {
            grouped.computeIfAbsent(field.getExampleId(), ignored -> new ArrayList<>()).add(field);
        }
        return grouped;
    }

    private void ensureEligibleSpansDoNotOverlap(String exampleId) {
        List<ParserFieldReview> eligible = fieldReviewRepository
                .findByExample_IdAndTrainingEligibleTrueOrderBySourceStartAsc(exampleId);
        int previousEnd = -1;
        for (ParserFieldReview field : eligible) {
            if (field.getSourceStart() == null || field.getSourceEnd() == null) {
                throw new IllegalArgumentException("Training approval blocked because source evidence is incomplete");
            }
            if (field.getSourceStart() < previousEnd) {
                throw new IllegalArgumentException(
                        "Training approval blocked because two fields use overlapping source evidence");
            }
            previousEnd = field.getSourceEnd();
        }
    }

    private ParserTrainingRecordDTO toTrainingRecord(
            ParserTrainingExample example, List<ParserFieldReview> fieldReviews) {
        List<ParserFieldReview> eligible = fieldReviews.stream()
                .filter(ParserFieldReview::isTrainingEligible)
                .sorted(Comparator.comparing(ParserFieldReview::getSourceStart))
                .toList();
        String privateText = maskPrivateDataPreservingOffsets(example.getRawPrompt(), fieldReviews);
        List<ParserTrainingSpanDTO> spans = eligible.stream()
                .filter(field -> field.getSourceStart() != null && field.getSourceEnd() != null)
                .map(field -> new ParserTrainingSpanDTO(
                        field.getFieldName(), field.getSourceStart(), field.getSourceEnd(),
                        privateText.substring(field.getSourceStart(), field.getSourceEnd()),
                        field.isCorrected()))
                .toList();
        return new ParserTrainingRecordDTO(
                example.getId(), example.getDatasetPartition().name(), example.getInputSource().name(),
                privateText, example.getParserVersion(), spans);
    }

    private String maskPrivateDataPreservingOffsets(String text, List<ParserFieldReview> fields) {
        char[] masked = text.toCharArray();
        maskPatternGroup(masked, text, PHONE_AFTER_CUE_PATTERN, 1);
        maskPattern(masked, text, PHONE_LIKE_IN_TEXT_PATTERN, true);
        maskPattern(masked, text, EMAIL_IN_TEXT_PATTERN, false);
        Matcher ownerMatcher = OWNER_NAME_IN_TEXT_PATTERN.matcher(text);
        while (ownerMatcher.find()) {
            for (int index = ownerMatcher.start(1); index < ownerMatcher.end(1); index++) {
                if (Character.isLetterOrDigit(masked[index])) masked[index] = 'X';
            }
        }
        for (ParserFieldReview field : fields) {
            if (!PRIVATE_FIELDS.contains(field.getFieldName())
                    || field.getSourceStart() == null || field.getSourceEnd() == null) continue;
            for (int index = field.getSourceStart(); index < field.getSourceEnd() && index < masked.length; index++) {
                if (Character.isLetterOrDigit(masked[index])) masked[index] = 'X';
            }
        }
        String privateText = new String(masked);
        if (PHONE_LIKE_IN_TEXT_PATTERN.matcher(privateText).find()
                || EMAIL_IN_TEXT_PATTERN.matcher(privateText).find()) {
            throw new IllegalStateException("Training export blocked because personal data remains unmasked");
        }
        return privateText;
    }

    private void maskPatternGroup(char[] masked, String text, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int index = matcher.start(group); index < matcher.end(group); index++) {
                if (!Character.isWhitespace(masked[index])) masked[index] = 'X';
            }
        }
    }

    private void maskPattern(char[] masked, String text, Pattern pattern, boolean digitsOnly) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int index = matcher.start(); index < matcher.end(); index++) {
                if (digitsOnly && Character.isDigit(masked[index])) masked[index] = 'X';
                if (!digitsOnly && !Character.isWhitespace(masked[index])) masked[index] = 'X';
            }
        }
    }

    private Map<String, String> snapshot(ParsedPropertyDTO dto) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "bhk", dto.getBhk());
        put(values, "type", dto.getType());
        put(values, "status", dto.getStatus());
        put(values, "city", dto.getCity());
        put(values, "sector", dto.getSector());
        put(values, "colony", dto.getColony());
        put(values, "rentAmount", dto.getRentAmount() == null ? null : formatNumber(dto.getRentAmount()));
        put(values, "brokerageVal", dto.getBrokerageVal());
        put(values, "brokerageDays", dto.getBrokerageDays());
        put(values, "areaSqFt", dto.getAreaSqFt());
        put(values, "depositVal", dto.getDepositVal());
        put(values, "bathrooms", dto.getBathrooms());
        put(values, "furnishingStatus", dto.getFurnishingStatus());
        put(values, "possessionDate", dto.getPossessionDate());
        put(values, "address", dto.getAddress());
        put(values, "state", dto.getState());
        put(values, "pincode", dto.getPincode());
        put(values, "landmark", dto.getLandmark());
        put(values, "ownerName", dto.getOwnerName());
        put(values, "ownerPhone", dto.getOwnerPhone());
        put(values, "vastuFacing", dto.getVastuFacing());
        if (dto.getAmenities() != null && !dto.getAmenities().isEmpty()) {
            put(values, "amenities", String.join(", ", dto.getAmenities()));
        }
        return values;
    }

    private void put(Map<String, String> values, String key, String value) {
        if (!isMissing(value)) values.put(key, value.trim());
    }

    private Map<String, String> readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored parser snapshot could not be read", exception);
        }
    }

    private String writeSnapshot(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Parser snapshot could not be stored", exception);
        }
    }

    private ParserTrainingExample findExample(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Example id is required");
        return exampleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Parser learning example not found"));
    }

    private void reject(ParserTrainingExample example, String reason) {
        example.setReviewStatus(ParserReviewStatus.REJECTED);
        example.setDatasetPartition(ParserDatasetPartition.EXCLUDED);
        example.setExclusionReason(reason);
        example.setCuratedAt(LocalDateTime.now());
        example.setReviewedBy(currentReviewer());
        exampleRepository.save(example);
    }

    private ParserDatasetPartition partitionForHash(String hash) {
        long bucket = Long.parseLong(hash.substring(0, 8), 16);
        long partitionBucket = bucket % 100;
        if (partitionBucket < HOLDOUT_PERCENT) return ParserDatasetPartition.HOLDOUT;
        if (partitionBucket < HOLDOUT_PERCENT + VALIDATION_PERCENT) {
            return ParserDatasetPartition.VALIDATION;
        }
        return ParserDatasetPartition.TRAIN;
    }

    private String currentReviewer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : "system";
    }

    private String safePrompt(String rawPrompt) {
        if (rawPrompt == null) return "";
        String trimmed = rawPrompt.trim();
        return trimmed.length() <= MAX_PROMPT_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_PROMPT_LENGTH);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private CanonicalText canonicalizeWithOffsets(String value) {
        StringBuilder canonical = new StringBuilder(value.length());
        List<Integer> indexes = new ArrayList<>(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '+') {
                canonical.append(Character.toLowerCase(character));
                indexes.add(index);
            }
        }
        return new CanonicalText(canonical.toString(), indexes);
    }

    private String canonical(String value) {
        StringBuilder canonical = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '+') {
                canonical.append(Character.toLowerCase(character));
            }
        }
        return canonical.toString();
    }

    private Long normalizedAmount(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.ROOT).replace(",", "").replace("₹", "").trim();
        Matcher matcher = PatternHolder.AMOUNT_TOKEN.matcher(normalized);
        if (!matcher.find()) return null;
        long amount = Long.parseLong(matcher.group(1));
        if (matcher.group(2) != null) amount *= 1_000L;
        return amount;
    }

    private String firstNumber(String value) {
        if (value == null) return null;
        Matcher matcher = PatternHolder.FIRST_NUMBER.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String numberWord(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "one" -> "1";
            case "two", "to", "too" -> "2";
            case "three" -> "3";
            case "four" -> "4";
            case "five" -> "5";
            case "six" -> "6";
            case "seven" -> "7";
            case "eight" -> "8";
            case "nine" -> "9";
            case "ten" -> "10";
            default -> value;
        };
    }

    private boolean equivalent(String first, String second) {
        if (isMissing(first) && isMissing(second)) return true;
        if (first == null || second == null) return false;
        return canonical(first).equals(canonical(second));
    }

    private boolean isMissing(String value) {
        return value == null || value.isBlank() || EMPTY_VALUE_PATTERN.matcher(value.trim()).matches();
    }

    private String emptyToNull(String value) {
        return isMissing(value) ? null : value;
    }

    private String formatNumber(Double value) {
        return value.doubleValue() == Math.rint(value)
                ? Long.toString(value.longValue())
                : value.toString();
    }

    private record Evidence(int start, int end) {}
    private record CanonicalText(String value, List<Integer> originalIndexes) {}

    private static final class PatternHolder {
        private static final Pattern AMOUNT_TOKEN = Pattern.compile("(\\d{1,7})(k)?", Pattern.CASE_INSENSITIVE);
        private static final Pattern FIRST_NUMBER = Pattern.compile("\\b(\\d{1,7})\\b");

        private PatternHolder() {
        }
    }
}
