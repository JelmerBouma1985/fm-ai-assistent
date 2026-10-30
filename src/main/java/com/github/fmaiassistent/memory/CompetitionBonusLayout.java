package com.github.fmaiassistent.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/** FM27 FULL_80 competition bonus row. */
public record CompetitionBonusLayout(int rowBytes, int dateRel, int conditionCompetitionRel,
        int stageUidRel, int secondaryStageUidRel, int conditionKindRel, int subjectCompetitionRel,
        int amountRel, int minimumGamesRel, int stateRel, int opaqueRel) {

    public static CompetitionBonusLayout current() {
        return new CompetitionBonusLayout(32, 0, 4, 8, 12, 16, 20, 24, 28, 30, 31);
    }

    /** No stage-name lookup, currency transformation or inferred opaque-state meaning. */
    public Row decode(String rawHex) {
        if (rawHex == null || rowBytes <= 0 || rowBytes > 256 || rawHex.length() != rowBytes * 2) {
            throw new IllegalArgumentException("Competition bonus row has invalid size");
        }
        var b = ByteBuffer.wrap(HexFormat.of().parseHex(rawHex)).order(ByteOrder.LITTLE_ENDIAN);
        int condition = b.getInt(conditionCompetitionRel);
        int subject = b.getInt(subjectCompetitionRel);
        int stage = b.getInt(stageUidRel);
        int secondaryStage = b.getInt(secondaryStageUidRel);
        if (condition < -1 || subject < -1) {
            throw new IllegalArgumentException("Competition bonus reference is below null sentinel");
        }
        return new Row(b.getInt(dateRel), condition, stage, secondaryStage, b.getInt(conditionKindRel),
                subject, b.getInt(amountRel), b.getShort(minimumGamesRel),
                Byte.toUnsignedInt(b.get(stateRel)), Byte.toUnsignedInt(b.get(opaqueRel)));
    }

    public record Row(int dateRaw, int conditionCompetitionIndex, int stageUid, int secondaryStageUid,
            int conditionKind, int subjectCompetitionIndex, int amountRaw, int minimumGames,
            int stateRaw, int opaqueRaw) { }
}
