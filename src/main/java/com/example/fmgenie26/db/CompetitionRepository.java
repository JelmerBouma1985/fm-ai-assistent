package com.example.fmgenie26.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface CompetitionRepository extends JpaRepository<CompetitionEntity, Long>, JpaSpecificationExecutor<CompetitionEntity> {
    Optional<CompetitionEntity> findBySourceAddress(Long sourceAddress);
}
