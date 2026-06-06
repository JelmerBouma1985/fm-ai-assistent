package com.example.fmgenie26.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlayerRepository extends JpaRepository<PlayerEntity, Long>, JpaSpecificationExecutor<PlayerEntity> {
}
