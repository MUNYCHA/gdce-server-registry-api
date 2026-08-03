package com.gdce.serverregistry.reachability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReachabilityController.class)
class ReachabilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthCheckService healthCheckService;

    @Test
    void checkReturnsOneResultPerServer() throws Exception {
        given(healthCheckService.checkAll()).willReturn(List.of(
                new CheckResult(1L, "prod-db-01", "10.0.1.15", 5432, true, 12L, null),
                new CheckResult(2L, "old-web", "10.0.2.9", 80, false, null, "connect timed out")));

        mockMvc.perform(post("/api/servers/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reachable").value(true))
                .andExpect(jsonPath("$[0].latencyMs").value(12))
                .andExpect(jsonPath("$[0].error").value(nullValue()))
                .andExpect(jsonPath("$[1].reachable").value(false))
                .andExpect(jsonPath("$[1].latencyMs").value(nullValue()))
                .andExpect(jsonPath("$[1].error").value("connect timed out"));
    }

    /** Servers being down is the answer, not an error condition. */
    @Test
    void checkWithEverythingUnreachableStillReturns200() throws Exception {
        given(healthCheckService.checkAll()).willReturn(List.of(
                new CheckResult(1L, "a", "10.255.255.1", 22, false, null, "connect timed out"),
                new CheckResult(2L, "b", "10.255.255.2", 22, false, null, "connect timed out")));

        mockMvc.perform(post("/api/servers/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reachable").value(false))
                .andExpect(jsonPath("$[1].reachable").value(false));
    }

    @Test
    void checkOnEmptyRegistryReturnsEmptyArray() throws Exception {
        given(healthCheckService.checkAll()).willReturn(List.of());

        mockMvc.perform(post("/api/servers/check"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
