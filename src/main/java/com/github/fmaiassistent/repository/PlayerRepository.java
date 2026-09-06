package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<PlayerEntity, Long>, JpaSpecificationExecutor<PlayerEntity> {

    @Query("""
            select player
            from PlayerEntity player
            left join fetch player.clubEntity c
            left join fetch player.playingClubEntity
            left join fetch c.competitionEntity
            """)
    List<PlayerEntity> findAllWithClubs();

    Optional<PlayerEntity> findFirstByUniqueId(Long uniqueId);

    List<PlayerEntity> findByUniqueIdIn(Collection<Long> uniqueIds);

}
