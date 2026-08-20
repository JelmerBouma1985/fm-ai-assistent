package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionTextFormatterTest {

    @Test
    void formatsAttackingMidfielderLeftAsAml() {
        assertEquals("AM(L)", PositionTextFormatter.format(playerWithPosition("AttackingMidfielderLeft")));
    }

    @Test
    void formatsAttackingMidfielderRightAsAmr() {
        assertEquals("AM(R)", PositionTextFormatter.format(playerWithPosition("AttackingMidfielderRight")));
    }

    private static PlayerEntity playerWithPosition(String position) {
        Map<String, Object> row = new HashMap<>();
        row.put(position, 20);
        return PlayerEntity.fromExportRow(row);
    }
}
