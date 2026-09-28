package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordContactDto;
import com.indore.pathome.spaces.service.LandlordContactService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lessor/contact")
@PreAuthorize("isAuthenticated()")
public class LandlordContactController {
    private final LandlordContactService contactService;

    public LandlordContactController(LandlordContactService contactService) {
        this.contactService = contactService;
    }

    @GetMapping
    public LandlordContactDto get(Authentication auth) {
        return contactService.getContact(auth.getName());
    }

    @PutMapping
    public LandlordContactDto update(Authentication auth, @RequestBody LandlordContactDto dto) {
        return contactService.updateContact(auth.getName(), dto);
    }
}
