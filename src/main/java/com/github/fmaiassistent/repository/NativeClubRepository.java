package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.NativeClubEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NativeClubRepository extends JpaRepository<NativeClubEntity, Long> {
    Page<NativeClubEntity> findByUefaCoefficientHexIsNotNullAndModuleSha256(String moduleSha256, Pageable pageable);
    long countByUefaCoefficientHexIsNotNullAndModuleSha256(String moduleSha256);
    Page<NativeClubEntity> findByUefaCoefficientHexIsNotNullAndModuleSha256AndNameContainingIgnoreCase(String moduleSha256, String name, Pageable pageable);
    long countByUefaCoefficientHexIsNotNullAndModuleSha256AndNameContainingIgnoreCase(String moduleSha256, String name);
}
