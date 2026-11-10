package org.fuin.porelo.planner.app;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Says that the planner is up, so its starter knows when to call it. */
@RestController
public class HealthController {

    /**
     * Answers that the planner is up.
     *
     * @return {@code {"status":"UP"}}.
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
