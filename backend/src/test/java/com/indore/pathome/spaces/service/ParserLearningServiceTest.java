package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.dto.ParserTrainingRecordDTO;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.ParserFieldReviewRepository;
import com.indore.pathome.spaces.repository.ParserTrainingExampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParserLearningServiceTest {

    @Mock
    private ParserTrainingExampleRepository exampleRepository;

    @Mock
    private ParserFieldReviewRepository fieldReviewRepository;

    private ParserLearningService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ParserLearningService(exampleRepository, fieldReviewRepository, objectMapper);
    }

    @Test
    void parseOutputStartsQuarantinedAndCannotEnterTrainingImmediately() {
        ParsedPropertyDTO property = reviewedProperty();

        service.quarantinePredictions(List.of(property), ParserInputSource.DICTATED);

        assertNotNull(property.getLearningExampleId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParserTrainingExample>> captor = ArgumentCaptor.forClass(List.class);
        verify(exampleRepository).saveAll(captor.capture());
        ParserTrainingExample stored = captor.getValue().get(0);
        assertEquals(ParserReviewStatus.QUARANTINED, stored.getReviewStatus());
        assertEquals(ParserDatasetPartition.EXCLUDED, stored.getDatasetPartition());
        assertEquals(ParserInputSource.DICTATED, stored.getInputSource());
        assertEquals(ParserLearningService.PARSER_VERSION, stored.getParserVersion());
    }

    @Test
    void publishedReviewOnlyMarksSourceSupportedSafeFieldsAsEligible() throws Exception {
        ParserTrainingExample existing = baseExample();
        existing.setInitialPredictionJson(objectMapper.writeValueAsString(Map.of(
                "bhk", "2 BHK",
                "type", "Flat",
                "sector", "Vijay Nagar",
                "rentAmount", "8000")));
        when(exampleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ParsedPropertyDTO reviewed = reviewedProperty();
        reviewed.setLearningExampleId(existing.getId());
        reviewed.setCity("Indore");
        reviewed.setOwnerPhone("+91 98765 43210");

        service.recordPublishedReview(reviewed, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParserFieldReview>> captor = ArgumentCaptor.forClass(List.class);
        verify(fieldReviewRepository).saveAll(captor.capture());
        List<ParserFieldReview> labels = captor.getValue();

        ParserFieldReview rent = field(labels, "rentAmount");
        assertTrue(rent.isCorrected());
        assertTrue(rent.isEvidenceSupported());
        assertTrue(rent.isTrainingEligible());
        assertEquals("18000", rent.getSourceText());

        ParserFieldReview city = field(labels, "city");
        assertFalse(city.isEvidenceSupported());
        assertFalse(city.isTrainingEligible());

        ParserFieldReview phone = field(labels, "ownerPhone");
        assertFalse(phone.isTrainingEligible());
        assertEquals("Personal data is retained for audit only", phone.getExclusionReason());

        assertEquals(ParserReviewStatus.PUBLISHED_PENDING_CURATION, existing.getReviewStatus());
        assertEquals(42L, existing.getPublishedListingId());
        assertTrue(existing.getEligibleLabelCount() >= 4);
    }

    @Test
    void curatorApprovalIsRequiredAndDuplicateTruthIsBlocked() {
        ParserTrainingExample pending = baseExample();
        pending.setReviewStatus(ParserReviewStatus.PUBLISHED_PENDING_CURATION);
        pending.setDatasetPartition(ParserDatasetPartition.TRAIN);
        pending.setEligibleLabelCount(3);
        pending.setFinalReviewedJson("{\"rentAmount\":\"18000\"}");
        when(exampleRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(exampleRepository.findTop20ByPromptHashAndReviewStatusOrderByCreatedAtDesc(
                pending.getPromptHash(), ParserReviewStatus.APPROVED_FOR_TRAINING))
                .thenReturn(List.of());
        when(fieldReviewRepository.findByExample_IdOrderByFieldNameAsc(pending.getId())).thenReturn(List.of());

        service.approveForTraining(pending.getId());

        assertEquals(ParserReviewStatus.APPROVED_FOR_TRAINING, pending.getReviewStatus());
        assertNotNull(pending.getCuratedAt());

        ParserTrainingExample duplicate = baseExample();
        duplicate.setId("duplicate-id");
        duplicate.setReviewStatus(ParserReviewStatus.PUBLISHED_PENDING_CURATION);
        duplicate.setDatasetPartition(ParserDatasetPartition.TRAIN);
        duplicate.setEligibleLabelCount(3);
        duplicate.setFinalReviewedJson(pending.getFinalReviewedJson());
        when(exampleRepository.findById(duplicate.getId())).thenReturn(Optional.of(duplicate));
        when(exampleRepository.findTop20ByPromptHashAndReviewStatusOrderByCreatedAtDesc(
                duplicate.getPromptHash(), ParserReviewStatus.APPROVED_FOR_TRAINING))
                .thenReturn(List.of(pending));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.approveForTraining(duplicate.getId()));
        assertTrue(error.getMessage().contains("identical reviewed example"));
        assertEquals(ParserReviewStatus.PUBLISHED_PENDING_CURATION, duplicate.getReviewStatus());
    }

    @Test
    void approvedDatasetMasksPrivateValuesWithoutChangingTrainingOffsets() {
        String text = "2 BHK flat rent 18000 owner 9876543210";
        ParserTrainingExample example = baseExample();
        example.setRawPrompt(text);
        example.setReviewStatus(ParserReviewStatus.APPROVED_FOR_TRAINING);
        example.setDatasetPartition(ParserDatasetPartition.TRAIN);

        ParserFieldReview bhk = eligibleField(example.getId(), "bhk", 0, 5, text);
        ParserFieldReview rent = eligibleField(example.getId(), "rentAmount", 16, 21, text);
        ParserFieldReview phone = new ParserFieldReview();
        phone.setExample(example);
        phone.setFieldName("ownerPhone");
        phone.setReviewedValue("9876543210");
        phone.setSourceStart(28);
        phone.setSourceEnd(38);

        when(exampleRepository.findByReviewStatusAndDatasetPartitionOrderByCreatedAtAsc(
                ParserReviewStatus.APPROVED_FOR_TRAINING,
                ParserDatasetPartition.TRAIN,
                PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(example)));
        when(fieldReviewRepository.findForExamples(List.of(example.getId())))
                .thenReturn(List.of(bhk, rent, phone));

        ParserTrainingRecordDTO record = service.approvedDataset(
                ParserDatasetPartition.TRAIN, PageRequest.of(0, 10)).getContent().get(0);

        assertFalse(record.text().contains("9876543210"));
        assertTrue(record.text().contains("XXXXXXXXXX"));
        assertEquals("18000", record.text().substring(16, 21));
        assertEquals(2, record.spans().size());
    }

    @Test
    void approvedDatasetMasksShortContactValuesAndEmailAddresses() {
        String text = "2 BHK rent 18000 owner phone 1234567 email owner@example.com";
        ParserTrainingExample example = baseExample();
        example.setRawPrompt(text);
        example.setReviewStatus(ParserReviewStatus.APPROVED_FOR_TRAINING);
        example.setDatasetPartition(ParserDatasetPartition.TRAIN);
        int rentStart = text.indexOf("18000");
        ParserFieldReview rent = eligibleField(
                example.getId(), "rentAmount", rentStart, rentStart + 5, text);

        when(exampleRepository.findByReviewStatusAndDatasetPartitionOrderByCreatedAtAsc(
                ParserReviewStatus.APPROVED_FOR_TRAINING,
                ParserDatasetPartition.TRAIN,
                PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(example)));
        when(fieldReviewRepository.findForExamples(List.of(example.getId())))
                .thenReturn(List.of(rent));

        ParserTrainingRecordDTO record = service.approvedDataset(
                ParserDatasetPartition.TRAIN, PageRequest.of(0, 10)).getContent().get(0);

        assertFalse(record.text().contains("1234567"));
        assertFalse(record.text().contains("owner@example.com"));
        assertEquals("18000", record.text().substring(rentStart, rentStart + 5));
    }

    @Test
    void directPublishCreatesTheLearningExampleBeforeItsFieldReviews() {
        ParsedPropertyDTO reviewed = reviewedProperty();
        when(exampleRepository.save(any(ParserTrainingExample.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordPublishedReview(reviewed, 84L);

        assertNotNull(reviewed.getLearningExampleId());
        var order = inOrder(exampleRepository, fieldReviewRepository);
        order.verify(exampleRepository).save(any(ParserTrainingExample.class));
        order.verify(fieldReviewRepository).deleteByExample_Id(reviewed.getLearningExampleId());
        order.verify(fieldReviewRepository).saveAll(anyList());
        order.verify(exampleRepository).save(any(ParserTrainingExample.class));
    }

    @Test
    void publishedReviewRejectsAClientSuppliedExampleFromAnotherPrompt() {
        ParserTrainingExample existing = baseExample();
        when(exampleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        ParsedPropertyDTO reviewed = reviewedProperty();
        reviewed.setLearningExampleId(existing.getId());
        reviewed.setRawPrompt("3 BHK flat in Palasia rent 45000");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.recordPublishedReview(reviewed, 99L));

        assertTrue(error.getMessage().contains("does not match this property description"));
        verify(fieldReviewRepository, never()).saveAll(anyList());
    }

    private ParsedPropertyDTO reviewedProperty() {
        ParsedPropertyDTO property = new ParsedPropertyDTO();
        property.setPromptIndex(1);
        property.setRawPrompt("2 BHK flat in Vijay Nagar rent 18000");
        property.setBhk("2 BHK");
        property.setType("Flat");
        property.setSector("Vijay Nagar");
        property.setRentAmount(18000.0);
        return property;
    }

    private ParserTrainingExample baseExample() {
        ParserTrainingExample example = new ParserTrainingExample();
        example.setId("example-id");
        example.setBatchId("batch-id");
        example.setPropertyIndex(1);
        example.setInputSource(ParserInputSource.DICTATED);
        example.setRawPrompt("2 BHK flat in Vijay Nagar rent 18000");
        example.setPromptHash(sha256(example.getRawPrompt()));
        example.setInitialPredictionJson("{}");
        example.setParserVersion(ParserLearningService.PARSER_VERSION);
        example.setCreatedBy("system");
        return example;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ParserFieldReview field(List<ParserFieldReview> fields, String name) {
        return fields.stream()
                .filter(field -> name.equals(field.getFieldName()))
                .findFirst()
                .orElseThrow();
    }

    private ParserFieldReview eligibleField(
            String exampleId, String fieldName, int start, int end, String text) {
        ParserFieldReview field = new ParserFieldReview();
        ParserTrainingExample example = new ParserTrainingExample();
        example.setId(exampleId);
        field.setExample(example);
        field.setFieldName(fieldName);
        field.setReviewedValue(text.substring(start, end));
        field.setSourceStart(start);
        field.setSourceEnd(end);
        field.setSourceText(text.substring(start, end));
        field.setEvidenceSupported(true);
        field.setValueValid(true);
        field.setTrainingEligible(true);
        return field;
    }
}
