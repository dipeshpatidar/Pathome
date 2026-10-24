package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.RoutingTaskRecheckCommand;
import com.indore.pathome.spaces.dto.RoutingUnclaimedWorkPage;
import com.indore.pathome.spaces.dto.VisitRequestRoutingTaskPage;
import com.indore.pathome.spaces.dto.VisitRequestRoutingTaskView;
import com.indore.pathome.spaces.service.VisitRequestRoutingTaskService;
import com.indore.pathome.spaces.service.RoutingOversightService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/automatic-routing/tasks")
public class VisitRequestRoutingTaskController {
    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final VisitRequestRoutingTaskService tasks;
    private final RoutingOversightService oversight;

    public VisitRequestRoutingTaskController(VisitRequestRoutingTaskService tasks,
                                             RoutingOversightService oversight) {
        this.tasks = tasks;
        this.oversight = oversight;
    }

    @GetMapping
    public ResponseEntity<VisitRequestRoutingTaskPage> list(Authentication authentication,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(tasks.list(StaffAuthentication.userId(authentication),
                        parseInteger(page, "page"), parseInteger(size, "size")));
    }

    @PostMapping("/{taskId}/recheck")
    public ResponseEntity<VisitRequestRoutingTaskView> recheck(Authentication authentication,
            @PathVariable String taskId, @RequestBody RoutingTaskRecheckCommand command) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(tasks.recheck(StaffAuthentication.userId(authentication),
                        parseLong(taskId, "taskId"), command));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<VisitRequestRoutingTaskView> read(Authentication authentication,
            @PathVariable String taskId) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(tasks.read(StaffAuthentication.userId(authentication),
                        parseLong(taskId, "taskId")));
    }

    @GetMapping("/unclaimed")
    public ResponseEntity<RoutingUnclaimedWorkPage> unclaimed(Authentication authentication,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(oversight.unclaimed(StaffAuthentication.userId(authentication),
                        parseInteger(page, "page"), parseInteger(size, "size")));
    }

    private static int parseInteger(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static Long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }
}
