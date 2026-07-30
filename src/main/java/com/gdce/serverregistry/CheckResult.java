package com.gdce.serverregistry;

/**
 * Outcome of one reachability probe. Never persisted — see design principle 2.
 *
 * <p>Build these through {@link #reachable} and {@link #unreachable} rather than the
 * canonical constructor: the two factories are what keep the field rules of section 4.4
 * true, namely that a latency and an error can never both be present.
 */
public record CheckResult(
        Long id,
        String hostname,
        String ipAddress,
        int port,
        boolean reachable,
        Long latencyMs,
        String error
) {

    static CheckResult reachable(Server server, long latencyMs) {
        return new CheckResult(
                server.getId(), server.getHostname(), server.getIpAddress(), server.getPort(),
                true, latencyMs, null);
    }

    static CheckResult unreachable(Server server, String error) {
        return new CheckResult(
                server.getId(), server.getHostname(), server.getIpAddress(), server.getPort(),
                false, null, error);
    }
}
