package com.example.serverregistry;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private static final int DEFAULT_PORT = 22;

    private final ServerRepository repository;
    private final HealthCheckService healthCheckService;

    public ServerController(ServerRepository repository, HealthCheckService healthCheckService) {
        this.repository = repository;
        this.healthCheckService = healthCheckService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerResponse create(@Valid @RequestBody ServerRequest request) {
        Server server = new Server(
                request.hostname(),
                request.ipAddress(),
                request.serverType(),
                request.port() == null ? DEFAULT_PORT : request.port());
        return ServerResponse.from(repository.save(server));
    }

    @GetMapping
    public List<ServerResponse> list() {
        return repository.findAll(ServerRepository.NEWEST_FIRST).stream()
                .map(ServerResponse::from)
                .toList();
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

    /** Distinct types in use, so the admin form can suggest them without restricting input. */
    @GetMapping("/types")
    public List<String> types() {
        return repository.findDistinctServerTypes();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("No server with id " + id);
        }
        repository.deleteById(id);
    }
}
