package com.gdce.serverregistry.server;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ServerRepository extends JpaRepository<Server, Long> {

    /**
     * The one registry ordering: newest first. Shared so that {@code GET /api/servers} and
     * {@code POST /api/servers/check} cannot drift apart — section 4.4 requires the check
     * results to arrive in the same order as the list.
     */
    Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    @Query("SELECT DISTINCT s.serverType FROM Server s ORDER BY s.serverType")
    List<String> findDistinctServerTypes();

    @Query("SELECT DISTINCT s.systemName FROM Server s ORDER BY s.systemName")
    List<String> findDistinctSystemNames();
}
