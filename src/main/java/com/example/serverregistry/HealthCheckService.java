package com.example.serverregistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reachability probing: one TCP connect per registered server, all of them concurrently.
 *
 * <p>Results are computed on demand and returned. Nothing is persisted — see design
 * principle 2.
 */
@Service
public class HealthCheckService {

    private final ServerRepository repository;
    private final int timeoutMs;

    public HealthCheckService(ServerRepository repository,
                              @Value("${healthcheck.timeout-ms}") int timeoutMs) {
        this.repository = repository;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Probes every registered server and returns one result each, in registry order.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}: the load below opens and
     * closes its own transaction, and the probing that follows happens with no persistence
     * session open. Holding one across the socket calls would cap real concurrency at the
     * connection pool size (10) however many virtual threads were spawned, and it would show
     * up as slowness rather than an error (constraint 10.1).
     *
     * <p>Wall-clock time is therefore roughly the probe timeout when servers are down, not
     * the timeout multiplied by the number of servers.
     */
    public List<CheckResult> checkAll() {
        List<Server> servers = repository.findAll(ServerRepository.NEWEST_FIRST);

        // close() blocks until every task has finished, so the futures are all done below.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CheckResult>> probes = servers.stream()
                    .map(server -> executor.submit(() -> probe(server)))
                    .toList();
            return probes.stream().map(HealthCheckService::result).toList();
        }
    }

    /**
     * {@link #probe} handles its own failures, so a task cannot complete exceptionally. If one
     * somehow does, surface it rather than reporting a server as unreachable on false evidence.
     */
    private static CheckResult result(Future<CheckResult> probe) {
        try {
            return probe.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Probe failed unexpectedly", e.getCause());
        }
    }

    /**
     * TCP-connects to one server and times it.
     *
     * <p>Connects to the literal {@code ip_address} — never the hostname, which is a display
     * label and would cost a DNS lookup here (constraint 10.2).
     *
     * <p>Never throws. Any failure becomes an unreachable result, because one dead server must
     * not fail the whole request.
     */
    CheckResult probe(Server server) {
        try (Socket socket = new Socket()) {
            long startNanos = System.nanoTime();
            socket.connect(new InetSocketAddress(server.getIpAddress(), server.getPort()), timeoutMs);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            return CheckResult.reachable(server, elapsedMs);
        } catch (Exception e) {
            return CheckResult.unreachable(server, describe(e));
        }
    }

    /** Some socket exceptions carry no message; a bare null tells an admin nothing. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
