package com.example.serverregistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The only tests that touch a real database.
 *
 * <p>Everything else in the suite mocks {@link ServerRepository}, which means the entity
 * mapping, the constraints in {@code schema.sql} and the JPQL of
 * {@link ServerRepository#findDistinctServerTypes()} are never executed. Those are exactly
 * the things that fail at runtime while the mocked tests stay green, so they are covered
 * here and nothing else is — duplicating the slice tests against a container would only
 * make the build slower.
 *
 * <p>Requires Docker. If it is unavailable this class fails rather than skipping: a test
 * that quietly does not run is the problem it was written to solve.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ServerRegistryIntegrationTest {

    /** Same major version as production (contract section 1). */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServerRepository repository;

    @BeforeEach
    void clearRegistry() {
        repository.deleteAll();
    }

    /**
     * The context starting at all proves the mapping: {@code ddl-auto=validate} has compared
     * every {@link Server} field against the table {@code schema.sql} created. This adds the
     * round trip, so a column that maps but reads back wrong is caught too.
     */
    @Test
    void entityRoundTripsThroughTheRealSchema() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"prod-db-01","ipAddress":"10.0.1.15","serverType":" db ","port":5432}
                                """))
                .andExpect(status().isCreated());

        Server saved = repository.findAll().getFirst();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getHostname()).isEqualTo("prod-db-01");
        assertThat(saved.getIpAddress()).isEqualTo("10.0.1.15");
        assertThat(saved.getServerType()).isEqualTo("DB");
        assertThat(saved.getPort()).isEqualTo(5432);
    }

    /**
     * {@code created_at} is written by the database default and read back via
     * {@code @Generated(INSERT)}. A mock cannot show that the application never supplies it.
     */
    @Test
    void createdAtIsPopulatedByTheDatabase() {
        Server saved = repository.save(new Server("h", "10.0.1.15", "DB", 22));

        assertThat(saved.getCreatedAt())
                .as("the DB default must have been read back after insert")
                .isNotNull();
    }

    /** The port default belongs to the application, not the column (section 4.1). */
    @Test
    void omittedPortIsSavedAs22() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"h","ipAddress":"10.0.1.15","serverType":"DB"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.port").value(22));

        assertThat(repository.findAll().getFirst().getPort()).isEqualTo(22);
    }

    /**
     * A genuine {@code uq_servers_ip_port} violation. The slice test throws
     * {@link DataIntegrityViolationException} from a mock, which proves the handler but not
     * that PostgreSQL actually raises it, nor that Spring translates it to that type.
     */
    @Test
    void duplicateIpAndPortReturns409FromTheRealConstraint() throws Exception {
        String body = """
                {"hostname":"first","ipAddress":"10.0.1.15","serverType":"DB","port":5432}
                """;

        mockMvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("A server with this IP and port already exists"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(repository.count()).isEqualTo(1);
    }

    /** Same IP on a different port is a different server, so the constraint must allow it. */
    @Test
    void sameIpOnAnotherPortIsAccepted() throws Exception {
        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"a","ipAddress":"10.0.1.15","serverType":"DB","port":5432}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hostname":"b","ipAddress":"10.0.1.15","serverType":"WEB","port":80}
                                """))
                .andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(2);
    }

    /**
     * {@code ck_servers_port} is unreachable through the API — Bean Validation rejects an
     * out-of-range port first. Going straight at the repository is the only way to prove the
     * constraint exists, and it is the last defence if validation is ever loosened.
     */
    @Test
    void checkConstraintRejectsAnOutOfRangePort() {
        assertThatThrownBy(() -> repository.saveAndFlush(new Server("h", "10.0.1.15", "DB", 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The {@code @Query} JPQL is only ever parsed when JPA starts, which the {@code @WebMvcTest}
     * slice does not do. A typo in it would ship undetected without this.
     */
    @Test
    void distinctTypesQueryReturnsEachValueOnceSorted() throws Exception {
        repository.save(new Server("a", "10.0.1.1", "WEB", 80));
        repository.save(new Server("b", "10.0.1.2", "DB", 5432));
        repository.save(new Server("c", "10.0.1.3", "WEB", 8080));

        mockMvc.perform(get("/api/servers/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("DB"))
                .andExpect(jsonPath("$[1]").value("WEB"));
    }

    /**
     * Ordering against real {@code TIMESTAMPTZ} values. Each save runs in its own transaction,
     * so each gets a distinct {@code now()} — a tie would need two inserts inside one
     * microsecond, which a round trip to the container rules out.
     */
    @Test
    void listIsOrderedNewestFirst() throws Exception {
        repository.save(new Server("older", "10.0.1.1", "WEB", 80));
        repository.save(new Server("newer", "10.0.1.2", "DB", 5432));

        mockMvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hostname").value("newer"))
                .andExpect(jsonPath("$[1].hostname").value("older"));
    }

    /**
     * The full path for section 10.1: load in a transaction, close it, then probe. Proves the
     * endpoint works against real rows and returns them in list order.
     */
    @Test
    void checkProbesRealRowsInListOrder() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            repository.save(new Server("down", "203.0.113.9", "WEB", 9999));
            repository.save(new Server("up", "127.0.0.1", "OTHER", listening.getLocalPort()));

            mockMvc.perform(post("/api/servers/check"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].hostname").value("up"))
                    .andExpect(jsonPath("$[0].reachable").value(true))
                    .andExpect(jsonPath("$[1].hostname").value("down"))
                    .andExpect(jsonPath("$[1].reachable").value(false));
        }
    }

    @Test
    void checkOnAnEmptyRegistryReturns200AndEmptyArray() throws Exception {
        mockMvc.perform(post("/api/servers/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Deleting a row that exists, against the real table rather than a stubbed existsById. */
    @Test
    void deleteRemovesTheRow() throws Exception {
        Server saved = repository.save(new Server("doomed", "10.0.1.15", "DB", 22));

        mockMvc.perform(delete("/api/servers/" + saved.getId()))
                .andExpect(status().isNoContent());

        assertThat(repository.findAll()).isEmpty();
    }
}
