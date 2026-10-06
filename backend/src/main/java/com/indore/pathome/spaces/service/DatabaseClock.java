package com.indore.pathome.spaces.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DatabaseClock {
    private final JdbcTemplate jdbc;

    public DatabaseClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rows, row) -> rows.getTimestamp(1).toInstant());
    }
}
