package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "fixtures")
public class FixtureEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_address")
    private Long sourceAddress;

    @Column(name = "kickoff_date", length = 32, nullable = false)
    private String kickoffDate;

    @Column(name = "kickoff_time", length = 16, nullable = false)
    private String kickoffTime;

    @Column(length = 1024)
    private String competition;

    @Column(name = "home_club", length = 1024)
    private String homeClub;

    @Column(name = "away_club", length = 1024)
    private String awayClub;

    @Column(length = 1024)
    private String opponent;

    @Column(length = 1024)
    private String venue;

    @Column(nullable = false)
    private Boolean home;

    @ManyToOne
    @JoinColumn(name = "home_club_id", foreignKey = @ForeignKey(name = "fk_fixtures_home_club"))
    private ClubEntity homeClubEntity;

    @ManyToOne
    @JoinColumn(name = "away_club_id", foreignKey = @ForeignKey(name = "fk_fixtures_away_club"))
    private ClubEntity awayClubEntity;

    @ManyToOne
    @JoinColumn(name = "opponent_club_id", foreignKey = @ForeignKey(name = "fk_fixtures_opponent_club"))
    private ClubEntity opponentClubEntity;

    protected FixtureEntity() {
    }

    public FixtureEntity(
            String kickoffDate, String kickoffTime, String competition,
            String homeClub, String awayClub, String opponent, String venue, boolean home) {
        this.kickoffDate = kickoffDate;
        this.kickoffTime = kickoffTime;
        this.competition = competition;
        this.homeClub = homeClub;
        this.awayClub = awayClub;
        this.opponent = opponent;
        this.venue = venue;
        this.home = home;
    }

    public Long getId() { return id; }
    public Long getSourceAddress() { return sourceAddress; }
    public String getKickoffDate() { return kickoffDate; }
    public String getKickoffTime() { return kickoffTime; }
    public String getCompetition() { return competition; }
    public String getHomeClub() { return homeClub; }
    public String getAwayClub() { return awayClub; }
    public String getOpponent() { return opponent; }
    public String getVenue() { return venue; }
    public Boolean getHome() { return home; }
    public ClubEntity getHomeClubEntity() { return homeClubEntity; }
    public ClubEntity getAwayClubEntity() { return awayClubEntity; }
    public ClubEntity getOpponentClubEntity() { return opponentClubEntity; }
}
