package com.example.fmgenie26.ui;

import com.example.fmgenie26.db.PlayerColumnNames;
import com.example.fmgenie26.db.PlayerEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PositionTextFormatterTest {
    @Test
    void mergesDefenderAndWingBackWithSameWideSides() {
        PlayerEntity player = player(Map.of(
                "DefenderRight", 20,
                "DefenderLeft", 20,
                "WingBackRight", 20,
                "WingBackLeft", 20));

        assertThat(PositionTextFormatter.format(player)).isEqualTo("D/WB (RL)");
    }

    @Test
    void keepsNonAdjacentWideLinesSeparated() {
        PlayerEntity player = player(Map.of(
                "DefenderLeft", 20,
                "WingBackLeft", 20,
                "AttackingMidfielderLeft", 20,
                "AttackingMidfielderRight", 20));

        assertThat(PositionTextFormatter.format(player)).isEqualTo("D/WB(L),AM(RL)");
    }

    @Test
    void usesFmStyleAttackingMidfielderSideText() {
        PlayerEntity player = player(Map.of(
                "AttackingMidfielderLeft", 20,
                "AttackingMidfielderCentral", 20,
                "Striker", 20));

        assertThat(PositionTextFormatter.format(player)).isEqualTo("AM(RC),ST");
    }

    private static PlayerEntity player(Map<String, Integer> positions) {
        PlayerEntity player = new TestPlayerEntity();
        positions.forEach((fieldName, value) -> set(player, PlayerColumnNames.toEntityFieldName(PlayerColumnNames.toColumnName(fieldName)), value));
        return player;
    }

    private static void set(PlayerEntity player, String fieldName, Integer value) {
        try {
            Field field = PlayerEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(player, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class TestPlayerEntity extends PlayerEntity {
        private TestPlayerEntity() {
            super();
        }
    }
}
