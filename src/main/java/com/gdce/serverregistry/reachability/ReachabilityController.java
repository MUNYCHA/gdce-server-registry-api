package com.gdce.serverregistry.reachability;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servers")
public class ReachabilityController {

    private final HealthCheckService healthCheckService;

    public ReachabilityController(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    /**
     * Tests every registered server and returns the results.
     *
     * <p>Always {@code 200} when the probing itself ran, including when every server is
     * unreachable: servers being down is the answer, not an error condition.
     */
    @PostMapping("/check")
    public List<CheckResult> check() {
        return healthCheckService.checkAll();
    }
}
