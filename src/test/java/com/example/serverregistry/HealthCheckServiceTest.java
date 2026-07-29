package com.example.serverregistry;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class HealthCheckServiceTest {

    /**
     * TEST-NET-3 (RFC 5737) on a port this sandbox does not intercept. Ports 80, 443 and 53 are
     * transparently proxied here and would report *any* address as reachable, so they cannot be
     * used to test the timeout path.
     */
    private static final String BLACKHOLE_IP = "203.0.113.5";
    private static final int BLACKHOLE_PORT = 9999;

    private static final int SHORT_TIMEOUT_MS = 300;

    /** Longer, so the fan-out assertion has room to tell concurrent from sequential. */
    private static final int CONCURRENCY_TIMEOUT_MS = 500;

    @Test
    void reachableServerReportsLatencyAndNoError() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            Server server = server("local-test", "127.0.0.1", listening.getLocalPort());

            CheckResult result = service(SHORT_TIMEOUT_MS).probe(server);

            assertThat(result.reachable()).isTrue();
            assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
            assertThat(result.error()).isNull();
            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.hostname()).isEqualTo("local-test");
            assertThat(result.ipAddress()).isEqualTo("127.0.0.1");
            assertThat(result.port()).isEqualTo(listening.getLocalPort());
        }
    }

    @Test
    void refusedConnectionReportsErrorAndNoLatency() throws Exception {
        int closedPort;
        try (ServerSocket temporary = new ServerSocket(0)) {
            closedPort = temporary.getLocalPort();
        } // closed here, so nothing is listening on that port any more

        CheckResult result = service(SHORT_TIMEOUT_MS)
                .probe(server("dead", "127.0.0.1", closedPort));

        assertThat(result.reachable()).isFalse();
        assertThat(result.latencyMs()).isNull();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    void unreachableHostTimesOutAndRespectsTheConfiguredTimeout() {
        Server server = server("blackhole", BLACKHOLE_IP, BLACKHOLE_PORT);

        long startNanos = System.nanoTime();
        CheckResult result = service(SHORT_TIMEOUT_MS).probe(server);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(result.reachable()).isFalse();
        assertThat(result.latencyMs()).isNull();
        assertThat(result.error()).isNotBlank();
        assertThat(elapsedMs)
                .as("must wait for the timeout, then give up promptly")
                .isGreaterThanOrEqualTo(SHORT_TIMEOUT_MS - 50L)
                .isLessThan(SHORT_TIMEOUT_MS * 10L);
    }

    @Test
    void probeNeverThrowsOnAnUnusableAddress() {
        Server server = server("garbage", "not.an.ip.address", 22);

        CheckResult result = service(SHORT_TIMEOUT_MS).probe(server);

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).as("a null message must fall back to something readable").isNotBlank();
    }

    /** Constraint 10.2: the hostname is a label and must never be resolved or connected to. */
    @Test
    void hostnameIsNeverResolved() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            Server server = server("this-host-does-not-exist.invalid", "127.0.0.1", listening.getLocalPort());

            CheckResult result = service(SHORT_TIMEOUT_MS).probe(server);

            assertThat(result.reachable())
                    .as("connecting via the unresolvable hostname would have failed")
                    .isTrue();
        }
    }

    @Test
    void checkAllOnAnEmptyRegistryReturnsAnEmptyList() {
        assertThat(service(SHORT_TIMEOUT_MS).checkAll()).isEmpty();
    }

    @Test
    void checkAllReturnsOneResultPerServerInRegistryOrder() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            Server up = server("up", "127.0.0.1", listening.getLocalPort(), 1L);
            Server down = server("down", BLACKHOLE_IP, BLACKHOLE_PORT, 2L);

            List<CheckResult> results = service(SHORT_TIMEOUT_MS, up, down).checkAll();

            assertThat(results).extracting(CheckResult::id).containsExactly(1L, 2L);
            assertThat(results.get(0).reachable()).isTrue();
            assertThat(results.get(0).latencyMs()).isNotNull();
            assertThat(results.get(0).error()).isNull();
            assertThat(results.get(1).reachable()).isFalse();
            assertThat(results.get(1).latencyMs()).isNull();
            assertThat(results.get(1).error()).isNotBlank();
        }
    }

    /** A whole registry being down is an answer, not a failure — nothing may propagate out. */
    @Test
    void checkAllSurvivesEveryServerBeingUnreachable() {
        Server[] registry = blackholes(4);

        List<CheckResult> results = service(SHORT_TIMEOUT_MS, registry).checkAll();

        assertThat(results).hasSize(4).allSatisfy(result -> {
            assertThat(result.reachable()).isFalse();
            assertThat(result.error()).isNotBlank();
        });
    }

    /**
     * The point of the fan-out: elapsed time tracks the timeout, not the number of servers.
     * Sequentially this would take {@code SERVERS × CONCURRENCY_TIMEOUT_MS} = 4 seconds.
     */
    @Test
    void checkAllProbesConcurrentlySoTimeoutsDoNotAccumulate() {
        int servers = 8;
        long sequentialMs = (long) servers * CONCURRENCY_TIMEOUT_MS;

        long startNanos = System.nanoTime();
        List<CheckResult> results = service(CONCURRENCY_TIMEOUT_MS, blackholes(servers)).checkAll();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(results).hasSize(servers).noneMatch(CheckResult::reachable);
        assertThat(elapsedMs)
                .as("%d servers timing out must cost about one timeout, not %d of them",
                        servers, servers)
                .isGreaterThanOrEqualTo(CONCURRENCY_TIMEOUT_MS - 50L)
                .isLessThan(sequentialMs / 2);
    }

    /** Distinct unroutable addresses, so every probe has to run its timeout out. */
    private static Server[] blackholes(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> server("down-" + i, "203.0.113." + i, BLACKHOLE_PORT, (long) i))
                .toArray(Server[]::new);
    }

    private static HealthCheckService service(int timeoutMs, Server... registry) {
        ServerRepository repository = mock(ServerRepository.class);
        given(repository.findAll(ServerRepository.NEWEST_FIRST)).willReturn(List.of(registry));
        return new HealthCheckService(repository, timeoutMs);
    }

    private static Server server(String hostname, String ipAddress, int port) {
        return server(hostname, ipAddress, port, 1L);
    }

    private static Server server(String hostname, String ipAddress, int port, long id) {
        Server server = new Server(hostname, ipAddress, "OTHER", port);
        ReflectionTestUtils.setField(server, "id", id);
        return server;
    }
}
