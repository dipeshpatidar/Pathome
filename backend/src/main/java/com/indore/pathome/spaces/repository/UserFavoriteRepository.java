package com.indore.pathome.spaces.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Repository
public class UserFavoriteRepository {
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO user_favorites (user_id, listing_id)
            VALUES (?, ?)
            ON CONFLICT ON CONSTRAINT uq_user_favorites_user_listing DO NOTHING
            """;
    private static final String DELETE = "DELETE FROM user_favorites WHERE user_id = ? AND listing_id = ?";

    private final JdbcTemplate jdbc;

    public UserFavoriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> findSavedActiveListingIds(Long userId, Collection<Long> propertyIds) {
        if (propertyIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(propertyIds.size(), "?"));
        String sql = "SELECT f.listing_id FROM user_favorites f " +
                "JOIN listings l ON l.id = f.listing_id " +
                "WHERE f.user_id = ? AND l.status = 'ACTIVE' AND f.listing_id IN (" + placeholders + ")";
        List<Object> parameters = new ArrayList<>(propertyIds.size() + 1);
        parameters.add(userId);
        parameters.addAll(propertyIds);
        return jdbc.queryForList(sql, Long.class, parameters.toArray());
    }

    public List<Long> findSavedActiveListingPage(Long userId, int limit, int offset) {
        String sql = "SELECT f.listing_id FROM user_favorites f " +
                "JOIN listings l ON l.id = f.listing_id " +
                "WHERE f.user_id = ? AND l.status = 'ACTIVE' " +
                "ORDER BY f.listing_id DESC LIMIT ? OFFSET ?";
        return jdbc.queryForList(sql, Long.class, userId, limit, offset);
    }

    public void saveIfAbsent(Long userId, Long propertyId) {
        jdbc.update(INSERT_IF_ABSENT, userId, propertyId);
    }

    public void remove(Long userId, Long propertyId) {
        jdbc.update(DELETE, userId, propertyId);
    }
}
