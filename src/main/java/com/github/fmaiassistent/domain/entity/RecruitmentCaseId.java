package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class RecruitmentCaseId implements Serializable {
    @Column(name = "career_key", nullable = false, length = 256)
    private String careerKey;

    @Column(name = "player_unique_id", nullable = false)
    private Long playerUniqueId;

    protected RecruitmentCaseId() {
    }

    public RecruitmentCaseId(String careerKey, Long playerUniqueId) {
        this.careerKey = careerKey;
        this.playerUniqueId = playerUniqueId;
    }

    public String getCareerKey() { return careerKey; }
    public Long getPlayerUniqueId() { return playerUniqueId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RecruitmentCaseId that)) return false;
        return Objects.equals(careerKey, that.careerKey)
                && Objects.equals(playerUniqueId, that.playerUniqueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(careerKey, playerUniqueId);
    }
}
