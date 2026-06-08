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
            left join fetch player.clubEntity
            left join fetch player.playingClubEntity
            """)
    List<PlayerEntity> findAllWithClubs();

    @Cacheable(cacheNames = JCacheConfiguration.NATIONS_CACHE)
    @Query("""
            select distinct club.nation
            from PlayerEntity player
            join player.playingClubEntity club
            where club.nation is not null and club.nation <> ''
            order by club.nation
            """)
    List<String> findDistinctPlayingNations();

    @Cacheable(cacheNames = JCacheConfiguration.COMPETITIONS_CACHE)
    @Query("""
            select distinct club.competition
            from PlayerEntity player
            join player.playingClubEntity club
            where club.competition is not null and club.competition <> ''
            order by club.competition
            """)
    List<String> findDistinctPlayingCompetitions();

    @Cacheable(cacheNames = JCacheConfiguration.CLUB_NAMES_CACHE)
    @Query("""
            select distinct player.club
            from PlayerEntity player
            where player.club is not null and player.club <> ''
            order by player.club
            """)
    List<String> findDistinctClubs();

    @Cacheable(cacheNames = JCacheConfiguration.PLAYING_CLUB_NAMES_CACHE)
    @Query("""
            select distinct player.playingClub
            from PlayerEntity player
            where player.playingClub is not null and player.playingClub <> ''
            order by player.playingClub
            """)
    List<String> findDistinctPlayingClubs();
}
