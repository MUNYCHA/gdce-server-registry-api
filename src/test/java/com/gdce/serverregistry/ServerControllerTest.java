package com.gdce.serverregistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ServerController.class)
class ServerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ServerRepository repository;

    /** Not scanned by {@code @WebMvcTest}; the controller would fail to construct without it. */
    @MockBean
    private HealthCheckService healthCheckService;

    @Test
    void createWithoutPortDefaultsTo22() throws Exception {
        given(repository.save(any(Server.class))).willAnswer(call -> persisted(call.getArgument(0), 1L));

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"prod-db-01","ipAddress":"10.0.1.15","serverType":"DB"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.hostname").value("prod-db-01"))
                .andExpect(jsonPath("$.ipAddress").value("10.0.1.15"))
                .andExpect(jsonPath("$.serverType").value("DB"))
                .andExpect(jsonPath("$.port").value(22))
                .andExpect(jsonPath("$.createdAt").value("2026-07-28T09:12:03Z"));

        ArgumentCaptor<Server> saved = ArgumentCaptor.forClass(Server.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPort()).isEqualTo(22);
    }

    @Test
    void createWithExplicitPortKeepsIt() throws Exception {
        given(repository.save(any(Server.class))).willAnswer(call -> persisted(call.getArgument(0), 1L));

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"prod-db-01","ipAddress":"10.0.1.15","serverType":"DB","port":5432}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.port").value(5432));
    }

    @Test
    void duplicateIpAndPortReturns409() throws Exception {
        given(repository.save(any(Server.class)))
                .willThrow(new DataIntegrityViolationException("uq_servers_ip_port"));

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"prod-db-01","ipAddress":"10.0.1.15","serverType":"DB","port":5432}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("A server with this IP and port already exists"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void invalidOctetReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"prod-db-01","ipAddress":"10.0.1.256","serverType":"DB"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0]").value("ipAddress: must be a valid IP address"));

        verify(repository, never()).save(any());
    }

    @Test
    void ipv6IsAccepted() throws Exception {
        given(repository.save(any(Server.class))).willAnswer(call -> persisted(call.getArgument(0), 1L));

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"v6","ipAddress":"2001:db8::1","serverType":"WEB","port":80}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void arbitraryServerTypeIsAccepted() throws Exception {
        given(repository.save(any(Server.class))).willAnswer(call -> persisted(call.getArgument(0), 1L));

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"queue-01","ipAddress":"10.0.1.15","serverType":"KAFKA"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serverType").value("KAFKA"));
    }

    @Test
    void serverTypeIsTrimmedAndUppercased() throws Exception {
        given(repository.save(any(Server.class))).willAnswer(call -> persisted(call.getArgument(0), 1L));

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":"  db  "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serverType").value("DB"));

        ArgumentCaptor<Server> saved = ArgumentCaptor.forClass(Server.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getServerType()).isEqualTo("DB");
    }

    @Test
    void emptyServerTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("serverType: must not be blank"));

        verify(repository, never()).save(any());
    }

    @Test
    void whitespaceOnlyServerTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("serverType: must not be blank"));

        verify(repository, never()).save(any());
    }

    @Test
    void overlongServerTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":"%s"}
                                """.formatted("X".repeat(31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("serverType: size must be between 0 and 30"));

        verify(repository, never()).save(any());
    }

    @Test
    void blankHostnameReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"  ","ipAddress":"10.0.1.15","serverType":"DB"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("hostname: must not be blank"));
    }

    @Test
    void portZeroReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":"DB","port":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("port: must be greater than or equal to 1"));
    }

    @Test
    void portAboveRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":"DB","port":70000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("port: must be less than or equal to 65535"));
    }

    @Test
    void listReturnsNewestFirst() throws Exception {
        Server older = persisted(new Server("old", "10.0.2.9", "WEB", 80), 1L);
        Server newer = persisted(new Server("new", "10.0.1.15", "DB", 5432), 2L);
        given(repository.findAll(any(Sort.class))).willReturn(List.of(newer, older));

        mockMvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[1].id").value(1));

        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(repository).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void emptyListReturnsEmptyArray() throws Exception {
        given(repository.findAll(any(Sort.class))).willReturn(List.of());

        mockMvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void typesReturnsDistinctValuesSorted() throws Exception {
        given(repository.findDistinctServerTypes()).willReturn(List.of("APP", "DB", "MOBILE", "WEB"));

        mockMvc.perform(get("/api/servers/types"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"APP\",\"DB\",\"MOBILE\",\"WEB\"]", true));
    }

    @Test
    void typesOnEmptyRegistryReturnsEmptyArray() throws Exception {
        given(repository.findDistinctServerTypes()).willReturn(List.of());

        mockMvc.perform(get("/api/servers/types"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void deleteExistingReturns204() throws Exception {
        given(repository.existsById(1L)).willReturn(true);

        mockMvc.perform(delete("/api/servers/1"))
                .andExpect(status().isNoContent());

        verify(repository).deleteById(1L);
    }

    @Test
    void deleteMissingReturns404() throws Exception {
        given(repository.existsById(anyLong())).willReturn(false);

        mockMvc.perform(delete("/api/servers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No server with id 999"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verify(repository, never()).deleteById(anyLong());
    }

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

    /** Mimics what the database does on insert: assigns the id and created_at. */
    private static Server persisted(Server server, long id) {
        ReflectionTestUtils.setField(server, "id", id);
        ReflectionTestUtils.setField(server, "createdAt",
                OffsetDateTime.of(2026, 7, 28, 9, 12, 3, 0, ZoneOffset.UTC));
        return server;
    }
}
