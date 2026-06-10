package com.example.fmgenie26.db;

import com.example.fmgenie26.config.JCacheConfiguration;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlayerRepository extends JpaRepository<PlayerEntity, Long>, JpaSpecificationExecutor<PlayerEntity> {

    @Cacheable(cacheNames = JCacheConfiguration.PLAYERS_CACHE)
    @Override
    List<PlayerEntity> findAll();

    @Cacheable(cacheNames = JCacheConfiguration.PLAYERS_WITH_CLUBS_CACHE)
    @Query("""
            select player
            from PlayerEntity player
            left join fetch player.clubEntity c
            left join fetch player.playingClubEntity
            left join fetch c.competitionEntity
            """)
    List<PlayerEntity> findAllWithClubs();

}
