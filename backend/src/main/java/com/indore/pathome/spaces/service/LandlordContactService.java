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
        LessorProfile profile = lessorProfiles.getOrCreateProfileForUser(user);
        boolean complete = isUsableName(profile.getDisplayName()) && isUsablePhone(profile.getMobileNumber());
        return new LandlordContactDto(profile.getDisplayName(), profile.getMobileNumber(), complete);
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
        lessorProfiles.getOrCreateProfileForUser(user);
        LessorProfile profile = lessorProfiles.updateProfileContact(user.getId(), cleanName, cleanPhone);

        return new LandlordContactDto(profile.getDisplayName(), profile.getMobileNumber(), true);
    }

    public static boolean isUsableName(String name) {
        return name != null && name.trim().length() >= 2 && name.trim().length() <= 100;
    }

    public static boolean isUsablePhone(String phone) {
        if (phone == null || phone.isBlank()) return false;
        String compact = phone.trim().replaceAll("[\\s\\-\\(\\)]", "");
        return COMPACT_INDIAN_MOBILE.matcher(compact).matches();
    }

    public static String sanitizeName(String name) {
        if (!isUsableName(name)) {
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
