package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class CompetitionBonusLayoutTest {
    // Independent live owner rows; checkpoint56 capture and native instruction evidence, not generated fixtures.
    private static final String STEPHEN = "01006c07ffffffff14000000ffffffff000000007d020000eba80100ffff0000";
    private static final String QUALIFICATION = "01006c077d020000ffffffffffffffff0a0000001600000000890000ffff0000";

    @Test void capturedWinningBonusRetainsUidKeyRatherThanRegistryIndex() throws Exception {
        var l=CompetitionBonusLayout.current();
        PlayerSnapshotValidator.validateCompetitionBonus(STEPHEN,l);
        var r=l.decode(STEPHEN);
        assertEquals(637,r.subjectCompetitionIndex());
        assertEquals(-1,r.conditionCompetitionIndex());
        assertEquals(20,r.stageUid()); // Native lookup UID20 resolves to index19, never direct index20.
        assertEquals(-1,r.secondaryStageUid());
        assertEquals(0,r.conditionKind());
        assertEquals(108779,r.amountRaw());
        assertEquals(-1,r.minimumGames());
        assertEquals(0,r.stateRaw());
        assertEquals(0,r.opaqueRaw());
    }

    @Test void capturedQualificationKeepsTwoDifferentCompetitionRelationships() throws Exception {
        var l=CompetitionBonusLayout.current();
        PlayerSnapshotValidator.validateCompetitionBonus(QUALIFICATION,l);
        var r=l.decode(QUALIFICATION);
        assertEquals(22,r.subjectCompetitionIndex()); // Independently resolved UID32 Italian Serie A.
        assertEquals(637,r.conditionCompetitionIndex()); // UID1301394 UEFA Champions League.
        assertEquals(10,r.conditionKind());
        assertEquals(35072,r.amountRaw());
        assertEquals(-1,r.stageUid());
        assertEquals(-1,r.secondaryStageUid());
    }

    @Test void everyShiftedLayoutRejectsTheSameCapturedRow() throws Exception {
        var fields=CompetitionBonusLayout.class.getRecordComponents();
        var types=new Class<?>[fields.length];
        java.util.Arrays.fill(types,int.class);
        var constructor=CompetitionBonusLayout.class.getDeclaredConstructor(types);
        var original=CompetitionBonusLayout.current();
        for(int shifted=0;shifted<fields.length;shifted++) {
            var args=new Object[fields.length];
            for(int i=0;i<fields.length;i++)args[i]=(int)fields[i].getAccessor().invoke(original)+(i==shifted?1:0);
            var drifted=constructor.newInstance(args);
            assertThrows(IOException.class,()->PlayerSnapshotValidator.validateCompetitionBonus(STEPHEN,drifted),fields[shifted].getName());
        }
    }

    @Test void retainsUnknownStatesAndSignedPrimitiveValuesWithoutInventingSemantics() {
        var bytes=HexFormat.of().parseHex(STEPHEN);
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(24,-123);b.putShort(28,(short)321);b.put(30,(byte)255);b.put(31,(byte)128);
        var r=CompetitionBonusLayout.current().decode(HexFormat.of().formatHex(bytes));
        assertEquals(-123,r.amountRaw());assertEquals(321,r.minimumGames());
        assertEquals(255,r.stateRaw());assertEquals(128,r.opaqueRaw());
    }

    @Test void malformedAndInvalidReferenceRowsAreRejected() {
        var l=CompetitionBonusLayout.current();
        for(String raw:new String[]{null,"",STEPHEN.substring(2),STEPHEN+"00","z".repeat(64)})
            assertThrows(IOException.class,()->PlayerSnapshotValidator.validateCompetitionBonus(raw,l));
        var raw=HexFormat.of().parseHex(STEPHEN);
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(4,-2);
        assertThrows(IOException.class,()->PlayerSnapshotValidator.validateCompetitionBonus(HexFormat.of().formatHex(raw),l));
    }

}
