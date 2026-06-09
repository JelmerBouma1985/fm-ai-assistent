package com.example.fmgenie26.db;

import com.example.fmgenie26.club.ClubExporter;
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
@Table(name = "clubs")
public class ClubEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 1024)
    private String name;

    @Column(name = "source_address")
    private Long sourceAddress;

    @Column(length = 1024)
    private String gender;

    @Column(length = 1024)
    private String competition;

    @ManyToOne
    @JoinColumn(name = "competition_id", foreignKey = @ForeignKey(name = "fk_clubs_competition"))
    private CompetitionEntity competitionEntity;

    @Column
    private Integer reputation;

    @Column(length = 1024)
    private String nation;

    @Column
    private Long balance;

    @Column(name = "transfer_budget")
    private Long transferBudget;

    @Column(name = "payroll_budget")
    private Long payrollBudget;

    protected ClubEntity() {
    }

    public static ClubEntity fromExportRow(Map<String, Object> row) {
        ClubEntity entity = new ClubEntity();
        for (String field : ClubExporter.FIELD_NAMES) {
            entity.setField(field, row.get(field));
        }
        return entity;
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ID", id);
        out.put("COMPETITION_ID", competitionEntity == null ? null : competitionEntity.getId());
        for (String field : ClubExporter.FIELD_NAMES) {
            out.put(toColumnName(field).toUpperCase(), getField(field));
        }
        return out;
    }

    public void setCompetitionEntity(CompetitionEntity competitionEntity) {
        this.competitionEntity = competitionEntity;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceAddress() {
        return sourceAddress;
    }

    public Integer getReputation() {
        return reputation;
    }

    public String getCompetition() {
        return competition;
    }

    public String getNation() {
        return nation;
    }

    private void setField(String fieldName, Object value) {
        try {
            Field field = ClubEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(this, convertValue(value, field.getType()));
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalArgumentException("unmapped club export field: " + fieldName, ex);
        }
    }

    private Object getField(String fieldName) {
        try {
            Field field = ClubEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalArgumentException("unmapped club export field: " + fieldName, ex);
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
        throw new IllegalArgumentException("unsupported club field type: " + targetType.getName());
    }

    private static String toColumnName(String fieldName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char ch = fieldName.charAt(i);
            if (Character.isUpperCase(ch)) {
                out.append('_').append(Character.toLowerCase(ch));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
