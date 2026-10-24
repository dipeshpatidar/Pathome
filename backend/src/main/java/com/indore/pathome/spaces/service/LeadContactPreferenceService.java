package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadContactPreferenceCommand;
import com.indore.pathome.spaces.dto.crm.LeadContactPreferenceView;
import com.indore.pathome.spaces.dto.crm.LeadDestinationRevealView;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import com.indore.pathome.spaces.entity.LeadConsentEvidenceMethod;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactChannel;
import com.indore.pathome.spaces.entity.LeadContactPreference;
import com.indore.pathome.spaces.entity.LeadDestinationChoice;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class LeadContactPreferenceService {
    public static final String COMMAND_ACTION = "LEAD_CONTACT_PREFERENCE_RECORD";
    private static final Pattern COUNTRY_CODE = Pattern.compile("\\+?[1-9]\\d{0,3}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern RESULT_REFERENCE =
            Pattern.compile("^preference:(\\d{1,18}):revision:(\\d{1,18}):leadVersion:(\\d{1,18})$");

    private final LeadRoutingQueueRepository leads;
    private final LeadContactPreferenceRepository preferences;
    private final LeadActivityRepository activities;
    private final LeadCommandReceiptRepository receipts;
    private final LeadAccessService leadAccess;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final LeadDataProtection protection;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LeadContactPreferenceService(LeadRoutingQueueRepository leads,
                                        LeadContactPreferenceRepository preferences,
                                        LeadActivityRepository activities,
                                        LeadCommandReceiptRepository receipts,
                                        LeadAccessService leadAccess,
                                        StaffAccessService staffAccess,
                                        OperationalSecurityGuards guards,
                                        OperationalAuditService audit,
                                        DatabaseClock databaseClock,
                                        LeadDataProtection protection,
                                        JdbcTemplate jdbc,
                                        ObjectMapper objectMapper) {
        this.leads = leads;
        this.preferences = preferences;
        this.activities = activities;
        this.receipts = receipts;
        this.leadAccess = leadAccess;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.protection = protection;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LeadContactPreferenceView record(Long actorId, Long leadId,
                                            LeadContactPreferenceCommand command) {
        return record(actorId, leadId, null, command);
    }

    @Transactional
    public LeadContactPreferenceView record(Long actorId, Long leadId, String operationKey,
                                            LeadContactPreferenceCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.channel() == null || command.destinationChoice() == null
                || command.consentStatus() == null) {
            throw new IllegalArgumentException(
                    "Lead version, channel, destination choice and consent status are required");
        }
        UUID operationId = optionalOperationId(operationKey);
        if (operationId != null) lockOperation(actorId, operationId);
        LeadRoutingQueue lead = lockAndAuthorize(actorId, leadId);
        String normalizedDestination = destination(lead, command);
        validateConsent(command, normalizedDestination);
        String payloadHash = operationId == null ? null
                : payloadHash(leadId, command, normalizedDestination);
        LeadCommandReceipt existingReceipt = operationId == null ? null
                : receipts.findByActorIdAndOperationIdAndAction(
                        actorId, operationId, COMMAND_ACTION).orElse(null);
        if (existingReceipt != null) {
            if (!existingReceipt.getPayloadHash().equals(payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different consent request");
            }
            return replay(lead, existingReceipt);
        }
        requireVersion(lead, command.expectedLeadVersion());
        requireActiveLifecycle(lead);

        LeadContactPreference current = preferences.findFirstByLead_IdOrderByRevisionDesc(leadId)
                .orElse(null);
        long currentRevision = current == null ? 0L : current.getRevision();
        if (command.expectedPreferenceRevision() != null
                && command.expectedPreferenceRevision() != currentRevision) {
            throw new VisitOperationsConflictException(
                    "Contact preference changed while this request was in progress");
        }

        LeadDataProtection.ProtectedValue protectedDestination = normalizedDestination == null
                ? null : protection.protect(normalizedDestination);
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        LeadContactPreference preference = new LeadContactPreference(lead, currentRevision + 1,
                command.channel(), command.destinationChoice(),
                command.destinationChoice() == LeadDestinationChoice.DIFFERENT
                        ? normalizedCallingCode(command.countryCallingCode()) : null,
                protectedDestination == null ? null : protectedDestination.ciphertext(),
                protectedDestination == null ? null : protectedDestination.iv(),
                protectedDestination == null ? null : protectedDestination.keyId(),
                protectedDestination == null ? null : protectedDestination.fingerprint(),
                command.consentStatus(),
                LeadContract.text(command.consentWordingVersion(), 64,
                        "Consent wording version", command.consentStatus() == LeadConsentStatus.GRANTED),
                command.evidenceMethod(), now, actor);
        preference = preferences.saveAndFlush(preference);

        lead.touchCrmState();
        lead = leads.saveAndFlush(lead);
        activities.save(new LeadActivity(lead, actor, "CONTACT_PREFERENCE_RECORDED",
                null, now, null));
        audit.recordUserEvent(actor, "LEAD_CONTACT_PREFERENCE_RECORDED", "LEAD", lead.getId(),
                "PROPERTY_SHARING_CONSENT", lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        if (operationId != null) {
            receipts.saveAndFlush(new LeadCommandReceipt(actor, operationId, COMMAND_ACTION,
                    payloadHash, resultReference(preference, lead), now));
        }
        return view(lead, preference, normalizedDestination);
    }

    static ResultReference parseResultReference(String value) {
        if (value == null) throw new EntityNotFoundException("Lead not found");
        var match = RESULT_REFERENCE.matcher(value);
        if (!match.matches()) throw new EntityNotFoundException("Lead not found");
        try {
            return new ResultReference(Long.parseLong(match.group(1)),
                    Long.parseLong(match.group(2)), Long.parseLong(match.group(3)));
        } catch (NumberFormatException exception) {
            throw new EntityNotFoundException("Lead not found");
        }
    }

    @Transactional
    public LeadDestinationRevealView reveal(Long actorId, Long leadId, Long revision) {
        LeadRoutingQueue lead = lockAndAuthorize(actorId, leadId);
        if (!leadAccess.context(actorId).canRevealContact(lead, actorId)) {
            throw new EntityNotFoundException("Lead not found");
        }
        LeadContactPreference preference = preferences.findByLead_IdAndRevision(leadId, revision)
                .orElseThrow(() -> new EntityNotFoundException("Contact preference not found"));
        if (preference.getDestinationCiphertext() == null) {
            throw new VisitOperationsConflictException("This preference has no communication destination");
        }
        String destination = protection.reveal(preference.getDestinationCiphertext(),
                preference.getDestinationIv(), preference.getDestinationKeyVersion());
        User actor = staffAccess.requireAttributableUser(actorId);
        audit.recordUserEvent(actor, "LEAD_DESTINATION_REVEALED", "LEAD", leadId,
                "OWNED_LEAD_DESTINATION", lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        return new LeadDestinationRevealView(leadId, revision, destination, preference.getRecordedAt());
    }

    @Transactional(readOnly = true)
    public List<LeadContactPreferenceView> history(Long actorId, Long leadId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        LeadRoutingQueue lead = leads.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireReadable(context, lead, actorId);
        LeadContactPreference latest = preferences.findFirstByLead_IdOrderByRevisionDesc(leadId)
                .orElse(null);
        return latest == null ? List.of() : List.of(view(lead, latest, null));
    }

    private LeadRoutingQueue lockAndAuthorize(Long actorId, Long leadId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long teamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), List.of(actorId));
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireEditable(context, lead, actorId);
        return lead;
    }

    private static void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (lead.getVersion() != null && !lead.getVersion().equals(expected)) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while this request was in progress. Refresh and compare before retrying.");
        }
    }

    private static void requireActiveLifecycle(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        if (status == LeadStatus.CLOSED || status == LeadStatus.ASSIGNED
                || status == LeadStatus.UNMAPPED || status == LeadStatus.CONTACTED
                || status == LeadStatus.STALE) {
            throw new VisitOperationsConflictException(
                    "Communication preferences cannot change for this Lead state");
        }
    }

    private static String destination(LeadRoutingQueue lead, LeadContactPreferenceCommand command) {
        return switch (command.destinationChoice()) {
            case NONE -> null;
            case SAME -> {
                if (lead.getContactPhoneNormalized() == null) {
                    throw new IllegalArgumentException(
                            "The Lead has no mobile number to use as the same destination");
                }
                yield lead.getContactPhoneNormalized();
            }
            case DIFFERENT -> {
                String callingCode = normalizedCallingCode(command.countryCallingCode());
                String local = command.number();
                if (callingCode == null || local == null || local.isBlank()) {
                    throw new IllegalArgumentException(
                            "A different destination requires an explicit country code and number");
                }
                if (!COUNTRY_CODE.matcher(command.countryCallingCode().trim()).matches()) {
                    throw new IllegalArgumentException("Country calling code is invalid");
                }
                String digits = PHONE_SEPARATORS.matcher(local.trim()).replaceAll("");
                if (!digits.matches("[1-9]\\d{6,14}")) {
                    throw new IllegalArgumentException(
                            "Destination number must be supplied without a local trunk prefix");
                }
                String combined = callingCode + digits;
                if (!E164.matcher(combined).matches()) {
                    throw new IllegalArgumentException(
                            "Destination must be a valid E.164 number with at most 15 digits");
                }
                yield combined;
            }
        };
    }

    private static String normalizedCallingCode(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.startsWith("+") ? trimmed : "+" + trimmed;
    }

    private static void validateConsent(LeadContactPreferenceCommand command,
                                        String normalizedDestination) {
        if (command.consentStatus() == LeadConsentStatus.GRANTED) {
            if (command.evidenceMethod() == null) {
                throw new IllegalArgumentException(
                        "Customer agreement requires an evidence method");
            }
            if (command.destinationChoice() != LeadDestinationChoice.NONE
                    && normalizedDestination == null) {
                throw new IllegalArgumentException(
                        "Granted property-sharing consent requires a destination");
            }
        }
        if (command.evidenceMethod() != null
                && command.evidenceMethod() != LeadConsentEvidenceMethod.CUSTOMER_VERBAL
                && command.evidenceMethod() != LeadConsentEvidenceMethod.CUSTOMER_DIGITAL) {
            throw new IllegalArgumentException("Consent evidence method is invalid");
        }
        if (command.channel() == LeadContactChannel.OTHER
                && command.destinationChoice() != LeadDestinationChoice.NONE) {
            // OTHER is still a named communication channel only when its destination is explicit.
            if (normalizedDestination == null) {
                throw new IllegalArgumentException("The agreed channel requires a destination");
            }
        }
    }

    private LeadContactPreferenceView view(LeadRoutingQueue lead,
                                           LeadContactPreference preference,
                                           String alreadyNormalized) {
        return view(lead, preference, alreadyNormalized, lead.getVersion());
    }

    private LeadContactPreferenceView view(LeadRoutingQueue lead,
                                           LeadContactPreference preference,
                                           String alreadyNormalized,
                                           Long resultLeadVersion) {
        String mask = null;
        if (preference.getDestinationCiphertext() != null) {
            String destination = alreadyNormalized == null
                    ? protection.reveal(preference.getDestinationCiphertext(),
                        preference.getDestinationIv(), preference.getDestinationKeyVersion())
                    : alreadyNormalized;
            mask = LeadDataProtection.mask(destination);
        }
        return new LeadContactPreferenceView(lead.getId(), preference.getRevision(), resultLeadVersion,
                preference.getChannel(), preference.getDestinationChoice(), mask,
                preference.getConsentStatus(), preference.getConsentPurpose(),
                preference.getConsentWordingVersion(), preference.getEvidenceMethod(),
                preference.getRecordedAt());
    }

    private LeadContactPreferenceView replay(LeadRoutingQueue lead, LeadCommandReceipt receipt) {
        ResultReference reference = parseResultReference(receipt.getResultReference());
        LeadContactPreference preference = preferences.findById(reference.preferenceId())
                .filter(value -> value.getLead().getId().equals(lead.getId()))
                .filter(value -> value.getRevision().equals(reference.revision()))
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        return view(lead, preference, null, reference.leadVersion());
    }

    private void lockOperation(Long actorId, UUID operationId) {
        String key = actorId + ":" + COMMAND_ACTION + ":" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, key), resultSet -> null);
    }

    private String payloadHash(Long leadId, LeadContactPreferenceCommand command,
                               String normalizedDestination) {
        ConsentCommandFingerprint identity = new ConsentCommandFingerprint(
                leadId, command.expectedLeadVersion(), command.expectedPreferenceRevision(),
                command.channel(), command.destinationChoice(),
                normalizedDestination == null ? null : normalizedCallingCode(command.countryCallingCode()),
                normalizedDestination == null ? "NO_DESTINATION" : protection.fingerprint(normalizedDestination),
                command.consentStatus(), command.consentWordingVersion(), command.evidenceMethod());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(identity)));
        } catch (Exception exception) {
            throw new IllegalStateException("Consent command could not be fingerprinted");
        }
    }

    private static String resultReference(LeadContactPreference preference,
                                          LeadRoutingQueue lead) {
        return "preference:" + preference.getId() + ":revision:" + preference.getRevision()
                + ":leadVersion:" + lead.getVersion();
    }

    private static UUID optionalOperationId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID");
        }
    }

    record ResultReference(Long preferenceId, Long revision, Long leadVersion) {}

    private record ConsentCommandFingerprint(
            Long leadId,
            Long expectedLeadVersion,
            Long expectedPreferenceRevision,
            LeadContactChannel channel,
            LeadDestinationChoice destinationChoice,
            String countryCallingCode,
            String destinationIdentity,
            LeadConsentStatus consentStatus,
            String consentWordingVersion,
            LeadConsentEvidenceMethod evidenceMethod) {}
}
