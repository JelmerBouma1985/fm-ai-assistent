package com.example.fmgenie26.db;

import com.example.fmgenie26.player.PlayerExporter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "players")
public class PlayerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_index", length = 1024)
    private String playerIndex;
    @Column(name = "record_address", length = 1024)
    private String recordAddress;
    @Column(length = 1024)
    private String name;
    @Column(length = 1024)
    private String gender;
    @Column(length = 1024)
    private String nationality;
    @Column(length = 1024)
    private String club;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id", foreignKey = @ForeignKey(name = "fk_players_club"))
    private ClubEntity clubEntity;
    @Column(name = "playing_club", length = 1024)
    private String playingClub;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playing_club_id", foreignKey = @ForeignKey(name = "fk_players_playing_club"))
    private ClubEntity playingClubEntity;
    @Column(name = "loan_club", length = 1024)
    private String loanClub;
    @Column(name = "is_loaned_out", length = 1024)
    private String isLoanedOut;
    @Column(name = "current_reputation")
    private Integer currentReputation;
    @Column(name = "home_reputation")
    private Integer homeReputation;
    @Column(name = "world_reputation")
    private Integer worldReputation;
    @Column
    private Integer ca;
    @Column
    private Integer pa;
    @Column(name = "asking_price")
    private Long askingPrice;
    @Column(name = "asking_price_raw")
    private Long askingPriceRaw;
    @Column(name = "contract_end_date", length = 1024)
    private String contractEndDate;
    @Column(name = "salary_pa")
    private Integer salaryPa;
    @Column(name = "salary_weekly_raw")
    private Integer salaryWeeklyRaw;
    @Column(name = "date_of_birth", length = 1024)
    private String dateOfBirth;
    @Column(length = 1024)
    private String age;
    @Column(name = "age_as_of", length = 1024)
    private String ageAsOf;
    @Column
    private Integer goalkeeper;
    @Column(name = "defender_left")
    private Integer defenderLeft;
    @Column(name = "defender_central")
    private Integer defenderCentral;
    @Column(name = "defender_right")
    private Integer defenderRight;
    @Column(name = "wing_back_left")
    private Integer wingBackLeft;
    @Column(name = "defensive_midfielder")
    private Integer defensiveMidfielder;
    @Column(name = "wing_back_right")
    private Integer wingBackRight;
    @Column(name = "midfielder_left")
    private Integer midfielderLeft;
    @Column(name = "midfielder_central")
    private Integer midfielderCentral;
    @Column(name = "midfielder_right")
    private Integer midfielderRight;
    @Column(name = "attacking_midfielder_left")
    private Integer attackingMidfielderLeft;
    @Column(name = "attacking_midfielder_central")
    private Integer attackingMidfielderCentral;
    @Column(name = "attacking_midfielder_right")
    private Integer attackingMidfielderRight;
    @Column
    private Integer striker;
    @Column
    private Integer crossing;
    @Column
    private Integer dribbling;
    @Column
    private Integer finishing;
    @Column
    private Integer heading;
    @Column(name = "long_shots")
    private Integer longShots;
    @Column
    private Integer marking;
    @Column(name = "off_the_ball")
    private Integer offTheBall;
    @Column
    private Integer passing;
    @Column
    private Integer penalties;
    @Column
    private Integer tackling;
    @Column
    private Integer vision;
    @Column
    private Integer handling;
    @Column(name = "aerial_ability")
    private Integer aerialAbility;
    @Column(name = "command_of_area")
    private Integer commandOfArea;
    @Column
    private Integer communication;
    @Column
    private Integer kicking;
    @Column
    private Integer throwing;
    @Column
    private Integer anticipation;
    @Column
    private Integer decisions;
    @Column(name = "one_on_ones")
    private Integer oneOnOnes;
    @Column
    private Integer positioning;
    @Column
    private Integer reflexes;
    @Column(name = "first_touch")
    private Integer firstTouch;
    @Column
    private Integer technique;
    @Column(name = "left_foot")
    private Integer leftFoot;
    @Column(name = "right_foot")
    private Integer rightFoot;
    @Column
    private Integer flair;
    @Column
    private Integer corners;
    @Column
    private Integer teamwork;
    @Column(name = "work_rate")
    private Integer workRate;
    @Column(name = "long_throws")
    private Integer longThrows;
    @Column
    private Integer eccentricity;
    @Column(name = "rushing_out")
    private Integer rushingOut;
    @Column(name = "tendency_to_punch")
    private Integer tendencyToPunch;
    @Column
    private Integer acceleration;
    @Column(name = "free_kicks")
    private Integer freeKicks;
    @Column
    private Integer strength;
    @Column
    private Integer stamina;
    @Column
    private Integer pace;
    @Column(name = "jumping_reach")
    private Integer jumpingReach;
    @Column
    private Integer leadership;
    @Column
    private Integer dirtiness;
    @Column
    private Integer balance;
    @Column
    private Integer bravery;
    @Column
    private Integer consistency;
    @Column
    private Integer aggression;
    @Column
    private Integer agility;
    @Column(name = "important_matches")
    private Integer importantMatches;
    @Column(name = "injury_proneness")
    private Integer injuryProneness;
    @Column
    private Integer versatility;
    @Column(name = "natural_fitness")
    private Integer naturalFitness;
    @Column
    private Integer determination;
    @Column
    private Integer composure;
    @Column
    private Integer concentration;

    protected PlayerEntity() {
    }

    public static PlayerEntity fromExportRow(Map<String, Object> row) {
        PlayerEntity entity = new PlayerEntity();
        for (String exportField : PlayerExporter.FIELD_NAMES) {
            entity.setExportField(exportField, row.get(exportField));
        }
        return entity;
    }

    public Long getId() {
        return id;
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ID", id);
        out.put("CLUB_ID", clubEntity == null ? null : clubEntity.getId());
        out.put("PLAYING_CLUB_ID", playingClubEntity == null ? null : playingClubEntity.getId());
        for (String exportField : PlayerExporter.FIELD_NAMES) {
            out.put(PlayerColumnNames.toColumnName(exportField).toUpperCase(), getExportField(exportField));
        }
        return out;
    }

    public void setClubEntity(ClubEntity clubEntity) {
        this.clubEntity = clubEntity;
    }

    public void setPlayingClubEntity(ClubEntity playingClubEntity) {
        this.playingClubEntity = playingClubEntity;
    }

    private void setExportField(String exportField, Object value) {
        try {
            Field field = PlayerEntity.class.getDeclaredField(PlayerColumnNames.toEntityFieldName(exportField));
            field.setAccessible(true);
            field.set(this, convertValue(value, field.getType()));
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalArgumentException("unmapped player export field: " + exportField, ex);
        }
    }

    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return null;
        }
        if (targetType == String.class) {
            return text;
        }
        if (targetType == Integer.class) {
            return Integer.valueOf(text);
        }
        if (targetType == Long.class) {
            return Long.valueOf(text);
        }
        throw new IllegalArgumentException("unsupported player field type: " + targetType.getName());
    }

    private Object getExportField(String exportField) {
        try {
            Field field = PlayerEntity.class.getDeclaredField(PlayerColumnNames.toEntityFieldName(exportField));
            field.setAccessible(true);
            return field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalArgumentException("unmapped player export field: " + exportField, ex);
        }
    }
}
