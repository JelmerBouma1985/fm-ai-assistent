package com.example.fmgenie26.config;

import org.springframework.boot.cache.autoconfigure.JCacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.EternalExpiryPolicy;

@Configuration
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
    JCacheManagerCustomizer playersCacheCustomizer() {
        return cacheManager -> {
            if (cacheManager.getCache(PLAYERS_CACHE) == null) {
                cacheManager.createCache(
                        PLAYERS_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }

            if (cacheManager.getCache(PLAYERS_WITH_CLUBS_CACHE) == null) {
                cacheManager.createCache(
                        PLAYERS_WITH_CLUBS_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }

            if (cacheManager.getCache(NATIONS_CACHE) == null) {
                cacheManager.createCache(
                        NATIONS_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }

            if (cacheManager.getCache(COMPETITIONS_CACHE) == null) {
                cacheManager.createCache(
                        COMPETITIONS_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }

            if (cacheManager.getCache(CLUB_NAMES_CACHE) == null) {
                cacheManager.createCache(
                        CLUB_NAMES_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }

            if (cacheManager.getCache(PLAYING_CLUB_NAMES_CACHE) == null) {
                cacheManager.createCache(
                        PLAYING_CLUB_NAMES_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }

            if (cacheManager.getCache(PLAYER_MAPPING_CACHE) == null) {
                cacheManager.createCache(
                        PLAYER_MAPPING_CACHE,
                        new MutableConfiguration<>()
                                .setStoreByValue(false)
                                .setStatisticsEnabled(true)
                                .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
                );
            }
        };
    }
}
