package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "recruitment_case")
public class RecruitmentCaseEntity {
    @EmbeddedId
    private RecruitmentCaseId id;

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

    @Column(name = "valid_until_game_date", length = 32)
    private String validUntilGameDate;

    @Column(name = "updated_at", length = 64)
    private String updatedAt;

    protected RecruitmentCaseEntity() {
    }

    public RecruitmentCaseEntity(String careerKey, Long playerUniqueId) {
        this.id = new RecruitmentCaseId(careerKey, playerUniqueId);
    }

    /** Creates a legacy-scoped row for compatibility with older callers and migrations. */
    public RecruitmentCaseEntity(Long playerUniqueId) {
        this("legacy", playerUniqueId);
    }

    public void update(
            String interestStatus,
            String dealStage,
            Long quotedFee,
            Long quotedWeeklyWage,
            String note,
            String source,
            String observedGameDate,
            String validUntilGameDate) {
        this.interestStatus = interestStatus;
        this.dealStage = dealStage;
        this.quotedFee = quotedFee;
        this.quotedWeeklyWage = quotedWeeklyWage;
        this.note = note;
        this.source = source;
        this.observedGameDate = observedGameDate;
        this.validUntilGameDate = validUntilGameDate;
        this.updatedAt = OffsetDateTime.now().toString();
    }

    public void update(
            String interestStatus,
            String dealStage,
            Long quotedFee,
            Long quotedWeeklyWage,
            String note,
            String source,
            String observedGameDate) {
        update(interestStatus, dealStage, quotedFee, quotedWeeklyWage, note, source,
                observedGameDate, observedGameDate);
    }

    public RecruitmentCaseId getId() { return id; }
    public String getCareerKey() { return id == null ? null : id.getCareerKey(); }
    public Long getPlayerUniqueId() { return id == null ? null : id.getPlayerUniqueId(); }
    public String getInterestStatus() { return interestStatus; }
    public String getDealStage() { return dealStage; }
    public Long getQuotedFee() { return quotedFee; }
    public Long getQuotedWeeklyWage() { return quotedWeeklyWage; }
    public String getNote() { return note; }
    public String getSource() { return source; }
    public String getObservedGameDate() { return observedGameDate; }
    public String getValidUntilGameDate() { return validUntilGameDate; }
    public String getUpdatedAt() { return updatedAt; }
}
