package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.RecruitmentCaseEntity;
import com.github.fmaiassistent.domain.entity.RecruitmentCaseId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecruitmentCaseRepository extends JpaRepository<RecruitmentCaseEntity, RecruitmentCaseId> {
    Optional<RecruitmentCaseEntity> findByIdCareerKeyAndIdPlayerUniqueId(String careerKey, Long playerUniqueId);
    List<RecruitmentCaseEntity> findByIdCareerKey(String careerKey);
}
