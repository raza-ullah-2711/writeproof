package com.writeproof.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: lets the app show the announcement and explain paused features before anyone tries. */
@RestController
class SystemStatusController {

    private final SystemSettings settings;

    SystemStatusController(SystemSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/api/system/status")
    SystemSettings.Status status() {
        return settings.status();
    }
}
