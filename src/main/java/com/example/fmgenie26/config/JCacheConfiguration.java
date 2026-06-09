package com.example.fmgenie26.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class JCacheConfiguration {

    public static final String PLAYERS_CACHE = "players";
    public static final String PLAYERS_WITH_CLUBS_CACHE = "players_with_clubs";
    public static final String NATIONS_CACHE = "nations";
    public static final String COMPETITIONS_CACHE = "competitions";
    public static final String CLUB_NAMES_CACHE = "club_names";
    public static final String PLAYING_CLUB_NAMES_CACHE = "playing_club_names";
    public static final String PLAYER_MAPPING_CACHE = "player_mapping_cache";

    @Bean
    CaffeineCacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                PLAYERS_CACHE,
                PLAYERS_WITH_CLUBS_CACHE,
                NATIONS_CACHE,
                COMPETITIONS_CACHE,
                CLUB_NAMES_CACHE,
                PLAYING_CLUB_NAMES_CACHE,
                PLAYER_MAPPING_CACHE
        );

        cacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .recordStats()
        );

        return cacheManager;
    }
}
