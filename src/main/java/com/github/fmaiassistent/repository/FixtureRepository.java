package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.FixtureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FixtureRepository extends JpaRepository<FixtureEntity, Long> {
    List<FixtureEntity> findAllByOrderByKickoffDateAscKickoffTimeAsc();
}
