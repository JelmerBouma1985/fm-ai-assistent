package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.config.JCacheConfiguration;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CompetitionRepository extends JpaRepository<CompetitionEntity, Long>, JpaSpecificationExecutor<CompetitionEntity> {

    @Cacheable(cacheNames = JCacheConfiguration.COMPETITIONS_CACHE)
    @Query("""
                select distinct c.name
                from CompetitionEntity c
                where c.name is not null
                order by c.name
            """)
    List<String> findDistinctNameByOrderByNameAsc();

    @Cacheable(cacheNames = JCacheConfiguration.NATIONS_CACHE)
    @Query("""
                select distinct c.nation
                from CompetitionEntity c
                where c.nation is not null
                order by c.nation
            """)
    List<String> findDistinctNations();

    @Cacheable(cacheNames = JCacheConfiguration.COMPETITIONS_CACHE)
    @Query("""
                select distinct c.gender
                from CompetitionEntity c
                where c.gender is not null
                order by c.gender
            """)
    List<String> findDistinctGenders();
}
