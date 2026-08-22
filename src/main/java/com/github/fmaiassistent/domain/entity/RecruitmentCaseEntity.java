package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "recruitment_case")
public class RecruitmentCaseEntity {
    @Id
    @Column(name = "player_unique_id")
    private Long playerUniqueId;

    @Column(name = "interest_status", length = 32)
    private String interestStatus;

    @Column(name = "deal_stage", length = 32)
    private String dealStage;

    @Column(name = "quoted_fee")
    private Long quotedFee;

    @Column(name = "quoted_weekly_wage")
    private Long quotedWeeklyWage;

    @Column(length = 2048)
    private String note;

    @Column(length = 32)
    private String source;

    @Column(name = "observed_game_date", length = 32)
    private String observedGameDate;

    @Column(name = "updated_at", length = 64)
    private String updatedAt;

    protected RecruitmentCaseEntity() {
    }

    public RecruitmentCaseEntity(Long playerUniqueId) {
        this.playerUniqueId = playerUniqueId;
    }

    public void update(
            String interestStatus,
            String dealStage,
            Long quotedFee,
            Long quotedWeeklyWage,
            String note,
            String source,
            String observedGameDate) {
        this.interestStatus = interestStatus;
        this.dealStage = dealStage;
        this.quotedFee = quotedFee;
        this.quotedWeeklyWage = quotedWeeklyWage;
        this.note = note;
        this.source = source;
        this.observedGameDate = observedGameDate;
        this.updatedAt = OffsetDateTime.now().toString();
    }

    public Long getPlayerUniqueId() { return playerUniqueId; }
    public String getInterestStatus() { return interestStatus; }
    public String getDealStage() { return dealStage; }
    public Long getQuotedFee() { return quotedFee; }
    public Long getQuotedWeeklyWage() { return quotedWeeklyWage; }
    public String getNote() { return note; }
    public String getSource() { return source; }
    public String getObservedGameDate() { return observedGameDate; }
    public String getUpdatedAt() { return updatedAt; }
}
