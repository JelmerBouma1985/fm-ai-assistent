package com.github.fmaiassistent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class JCacheConfiguration {

    public static final String NATIONS_CACHE = "nations";
    public static final String COMPETITIONS_CACHE = "competitions";
    public static final String COMPETITION_GENDERS_CACHE = "competition_genders";
    public static final String CLUB_NAMES_CACHE = "club_names";

    @Bean
    CaffeineCacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                NATIONS_CACHE,
                COMPETITIONS_CACHE,
                COMPETITION_GENDERS_CACHE,
                CLUB_NAMES_CACHE
        );

        cacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterAccess(java.time.Duration.ofMinutes(30))
                        .recordStats()
        );

        return cacheManager;
    }
}
