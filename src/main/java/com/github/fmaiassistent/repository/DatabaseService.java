package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.config.JCacheConfiguration;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DatabaseService {

    private final List<String> TABLES_TO_TRUNCATE = List.of(
            "PLAYERS",
            "CLUBS",
            "COMPETITIONS",
            "LOAD_METADATA"
    );
    private final JdbcTemplate jdbcTemplate;

    public DatabaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_WITH_CLUBS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.NATIONS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.COMPETITIONS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.CLUB_NAMES_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.CLUB_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYER_MAPPING_CACHE, allEntries = true)
    })
    @Transactional
    public void clearAllTables() {
        // DELETE is intentionally used instead of TRUNCATE. The complete RAM load runs in
        // one transaction, so a failed read can roll back and leave the previous snapshot
        // available to the UI and MCP clients.
        for (String table : TABLES_TO_TRUNCATE) {
            jdbcTemplate.execute("DELETE FROM " + quote(table));
        }
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
