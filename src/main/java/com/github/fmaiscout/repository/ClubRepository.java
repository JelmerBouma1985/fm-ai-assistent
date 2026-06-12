package com.github.fmaiscout.repository;

import com.github.fmaiscout.config.JCacheConfiguration;
import com.github.fmaiscout.domain.entity.ClubEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ClubRepository extends JpaRepository<ClubEntity, Long>, JpaSpecificationExecutor<ClubEntity> {

    @Cacheable(cacheNames = JCacheConfiguration.CLUB_NAMES_CACHE)
    @Query("""
                select distinct c.name
                from ClubEntity c
                where c.name is not null
                order by c.name
            """)
    List<String> findDistinctNameByOrderByNameAsc();

    @Cacheable(cacheNames = JCacheConfiguration.CLUB_CACHE)
    @Override
    List<ClubEntity> findAll();
}
