package com.gdce.serverregistry.server;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    public ServerController(ServerRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerResponse create(@Valid @RequestBody ServerRequest request) {
        Server server = new Server(
                request.hostname(),
                request.ipAddress(),
                request.serverType(),
                request.systemName(),
                request.port() == null ? DEFAULT_PORT : request.port());
        return ServerResponse.from(repository.save(server));
    }

    @GetMapping
    public List<ServerResponse> list() {
        return repository.findAll(ServerRepository.NEWEST_FIRST).stream()
                .map(ServerResponse::from)
                .toList();
    }

    /** Distinct types in use, so the admin form can suggest them without restricting input. */
    @GetMapping("/types")
    public List<String> types() {
        return repository.findDistinctServerTypes();
    }

    /** Distinct systems in use, so the admin form can suggest them without restricting input. */
    @GetMapping("/systems")
    public List<String> systems() {
        return repository.findDistinctSystemNames();
    }

    @PutMapping("/{id}")
    public ServerResponse update(@PathVariable Long id, @Valid @RequestBody ServerRequest request) {
        Server server = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No server with id " + id));
        server.setHostname(request.hostname());
        server.setIpAddress(request.ipAddress());
        server.setServerType(request.serverType());
        server.setSystemName(request.systemName());
        server.setPort(request.port() == null ? DEFAULT_PORT : request.port());
        return ServerResponse.from(repository.save(server));
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
