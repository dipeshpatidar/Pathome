package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.AutomaticRoutingControlCommand;
import com.indore.pathome.spaces.dto.AutomaticRoutingControlView;
import com.indore.pathome.spaces.service.AutomaticRoutingControlService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/admin/automatic-routing-control")
public class AutomaticRoutingControlController {
    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final AutomaticRoutingControlService controls;

    public AutomaticRoutingControlController(AutomaticRoutingControlService controls) {
        this.controls = controls;
    }

    @GetMapping
    public ResponseEntity<AutomaticRoutingControlView> read(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(controls.read(StaffAuthentication.userId(authentication)));
    }

    @PostMapping
    public ResponseEntity<AutomaticRoutingControlView> change(Authentication authentication,
            @RequestBody AutomaticRoutingControlCommand command) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(controls.change(StaffAuthentication.userId(authentication), command));
    }
}
