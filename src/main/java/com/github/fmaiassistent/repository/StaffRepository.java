package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.StaffEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<StaffEntity, Long>, JpaSpecificationExecutor<StaffEntity> {
    @Query("select staff from StaffEntity staff left join fetch staff.clubEntity order by lower(staff.name)")
    List<StaffEntity> findAllWithClubs();

    @Override
    @EntityGraph(attributePaths = "clubEntity")
    List<StaffEntity> findAll(Specification<StaffEntity> specification);

    @Override
    @EntityGraph(attributePaths = "clubEntity")
    Page<StaffEntity> findAll(Specification<StaffEntity> specification, Pageable pageable);

    Optional<StaffEntity> findFirstByUniqueId(Long uniqueId);
}
