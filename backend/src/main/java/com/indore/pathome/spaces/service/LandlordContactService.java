package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordContactDto;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LandlordContactService {
    private static final Pattern COMPACT_INDIAN_MOBILE = Pattern.compile("^(?:\\+?91)?([6-9]\\d{9})$");
    private static final Pattern LEGACY_LESSOR_PLACEHOLDER = Pattern.compile("^Lessor\\s+\\d+$", Pattern.CASE_INSENSITIVE);
    private final UserRepository users;
    private final LessorProfileService lessorProfiles;

    public LandlordContactService(UserRepository users, LessorProfileService lessorProfiles) {
        this.users = users;
        this.lessorProfiles = lessorProfiles;
    }

    @Transactional
    public LandlordContactDto getContact(String email) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        return lessorProfiles.getProfileForUser(user.getId())
                .map(profile -> contact(profile.getDisplayName(), profile.getMobileNumber()))
                .orElseGet(() -> contact(user.getFullName(), user.getPhoneNumber()));
    }

    @Transactional
    public LandlordContactDto updateContact(String email, LandlordContactDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Contact details required");
        }
        String cleanName = sanitizeName(dto.fullName());
        String cleanPhone = normalizePhone(dto.phoneNumber());

        User user = users.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        if (lessorProfiles.getProfileForUser(user.getId()).isPresent()) {
            LessorProfile profile = lessorProfiles.updateProfileContact(user.getId(), cleanName, cleanPhone);
            return contact(profile.getDisplayName(), profile.getMobileNumber());
        }
        // Before submission, the account owns its drafts and holds contact until the profile is created.
        user.setFullName(cleanName);
        user.setPhoneNumber(cleanPhone);
        users.save(user);
        return contact(cleanName, cleanPhone);
    }

    private static LandlordContactDto contact(String name, String phone) {
        return new LandlordContactDto(name, phone, isUsableName(name) && isUsablePhone(phone));
    }

    public static boolean isLegacyPlaceholderName(String name) {
        return name != null && LEGACY_LESSOR_PLACEHOLDER.matcher(name.trim()).matches();
    }

    public static boolean isUsableName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 100) {
            return false;
        }
        return !LEGACY_LESSOR_PLACEHOLDER.matcher(trimmed).matches();
    }

    public static boolean isUsablePhone(String phone) {
        if (phone == null || phone.isBlank()) return false;
        String compact = phone.trim().replaceAll("[\\s\\-\\(\\)]", "");
        return COMPACT_INDIAN_MOBILE.matcher(compact).matches();
    }

    public static String sanitizeName(String name) {
        if (!isUsableName(name)) {
            if (isLegacyPlaceholderName(name)) {
                throw new IllegalArgumentException("Enter a genuine full name instead of a placeholder");
            }
            throw new IllegalArgumentException("Enter a full name between 2 and 100 characters");
        }
        return name.trim();
    }

    public static String normalizePhone(String phone) {
        if (phone == null) {
            throw new IllegalArgumentException("Enter a valid 10-digit mobile number");
        }
        String compact = phone.trim().replaceAll("[\\s\\-\\(\\)]", "");
        Matcher matcher = COMPACT_INDIAN_MOBILE.matcher(compact);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Enter a valid 10-digit mobile number");
        }
        return "+91 " + matcher.group(1);
    }
}
