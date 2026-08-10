CREATE TABLE IF NOT EXISTS servers (
    id          BIGSERIAL PRIMARY KEY,
    hostname    VARCHAR(255) NOT NULL,
    ip_address  VARCHAR(45)  NOT NULL,
    server_type VARCHAR(30)  NOT NULL,
    system_name VARCHAR(60)  NOT NULL,
    port        INT          NOT NULL DEFAULT 22,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_servers_ip_port UNIQUE (ip_address, port),
    CONSTRAINT ck_servers_port CHECK (port BETWEEN 1 AND 65535)
);
