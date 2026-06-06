package com.example.fmgenie26.db;

import com.example.fmgenie26.competition.CompetitionExporter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "competitions")
public class CompetitionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 1024)
    private String name;

    @Column(name = "source_address")
    private Long sourceAddress;

    @Column(length = 1024)
    private String nation;

    @Column
    private Integer reputation;

    @Column(length = 1024)
    private String gender;

    protected CompetitionEntity() {
    }

    public static CompetitionEntity fromExportRow(Map<String, Object> row) {
        CompetitionEntity entity = new CompetitionEntity();
        for (String field : CompetitionExporter.FIELD_NAMES) {
            entity.setField(field, row.get(field));
        }
        return entity;
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ID", id);
        for (String field : CompetitionExporter.FIELD_NAMES) {
            out.put(toColumnName(field).toUpperCase(), getField(field));
        }
        return out;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceAddress() {
        return sourceAddress;
    }

    private void setField(String fieldName, Object value) {
        try {
            Field field = CompetitionEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(this, convertValue(value, field.getType()));
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalArgumentException("unmapped competition export field: " + fieldName, ex);
        }
    }

    private Object getField(String fieldName) {
        try {
            Field field = CompetitionEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalArgumentException("unmapped competition export field: " + fieldName, ex);
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
        throw new IllegalArgumentException("unsupported competition field type: " + targetType.getName());
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
