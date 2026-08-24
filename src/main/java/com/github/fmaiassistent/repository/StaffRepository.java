package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.config.JCacheConfiguration;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<StaffEntity, Long> {
    @Cacheable(cacheNames = JCacheConfiguration.STAFF_WITH_CLUBS_CACHE)
    @Query("select staff from StaffEntity staff left join fetch staff.clubEntity order by lower(staff.name)")
    List<StaffEntity> findAllWithClubs();

    Optional<StaffEntity> findFirstByUniqueId(Long uniqueId);
}
