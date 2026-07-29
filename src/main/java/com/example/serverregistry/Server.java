package com.example.serverregistry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "servers")
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hostname", nullable = false, length = 255)
    private String hostname;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    /** Free-form label, normalised to upper case by {@link ServerRequest}. */
    @Column(name = "server_type", nullable = false, length = 30)
    private String serverType;

    @Column(name = "port", nullable = false)
    private int port;

    /** Written by the database default; read back after insert, never written by the application. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Server() {
        // for JPA
    }

    public Server(String hostname, String ipAddress, String serverType, int port) {
        this.hostname = hostname;
        this.ipAddress = ipAddress;
        this.serverType = serverType;
        this.port = port;
    }

    public Long getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getServerType() {
        return serverType;
    }

    public int getPort() {
        return port;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
