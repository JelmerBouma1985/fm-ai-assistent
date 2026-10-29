package com.github.fmaiassistent.memory;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ClubFinancialHistoryLayoutTest {
    static final ClubFinancialHistoryLayout L=ClubFinancialHistoryLayout.current();
    public static byte[] payload(List<Integer> balances,int cursor,List<Integer> wages,int baseCursor) {
        var b=ByteBuffer.allocate(16714).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)1);ring(b,balances,cursor,48,0);
        b.put((byte)(wages==null?0:1));if(wages!=null)ring(b,wages,baseCursor,228,16);return Arrays.copyOf(b.array(),b.position());
    }
    private static void ring(ByteBuffer b,List<Integer> values,int cursor,int bytes,int offset){b.put((byte)values.size()).put((byte)cursor).put(new byte[14]);
        for(var v:values){b.put((byte)(v==null?0:1));if(v!=null){byte[] raw=new byte[bytes];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,v);if(bytes==48)ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(8,-5);b.put(raw);}}}
    @Test void countsCursorWrapSignedValuesNullAndMissingSourcesRemainDistinct() {
        byte[] raw=payload(Arrays.asList(-268750443,null,1182157850),59,List.of(0),2);raw[3]=0x12;raw[10]=0x34;
        var p=L.decode(raw,0x07F41A8D);assertThat(p.summary().members()).extracting(ClubFinancialHistoryLayout.Member::physicalSlot).containsExactly(59,0,1);
        assertThat(p.summary().unassignedPrefixHex()).startsWith("12");assertThat(p.summary().unassignedSuffixHex()).startsWith("34");
        assertThat(p.balances()).extracting(ClubFinancialHistoryLayout.Point::amount).containsExactly(-268750443,null,1182157850);
        assertThat(p.transferBudgets()).extracting(ClubFinancialHistoryLayout.Point::amount).containsExactly(-5,null,-5);
        assertThat(p.balances()).extracting(ClubFinancialHistoryLayout.Point::month).containsExactly(LocalDate.of(2036,2,1),LocalDate.of(2036,3,1),LocalDate.of(2036,4,1));
        assertThat(p.playerWages()).hasSize(1);assertThat(p.playerWages().getFirst().month()).isEqualTo(LocalDate.of(2036,4,1));assertThat(p.playerWages().getFirst().amount()).isZero();
        var absent=L.decode(payload(List.of(1,2),0,null,0),0x07F41A8D);assertThat(absent.expenditure()).isNull();assertThat(absent.playerWages()).extracting(ClubFinancialHistoryLayout.Point::amount).containsOnlyNulls();
        var empty=L.decode(payload(List.of(1,2),0,List.of(),0),0x07F41A8D);assertThat(empty.expenditure()).isNotNull();assertThat(empty.playerWages()).isEmpty();
        assertThat(L.decode(payload(List.of(1),0,List.of(7,8),0),0x07F41A8D).playerWages()).extracting(ClubFinancialHistoryLayout.Point::amount).containsExactly(7);
        assertThatThrownBy(()->p.summary().members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void boundaryLeapCenturyAndYearCoordinatesMatchNativeInstructionEvidence() {
        byte[] raw=payload(List.of(1,2,3),0,null,0);
        for(int y:new int[]{2000,2036,2100,2400})for(int m=1;m<=12;m++)for(int dom:new int[]{1,2})for(int e:new int[]{0,24,25,127}){
            LocalDate date=LocalDate.of(y,m,dom);int clock=y<<16|e<<9|date.getDayOfYear();var p=L.decode(raw,clock);
            int hhmm=e==0?0:e==127?1755:e==24?1145:1200;YearMonth last=YearMonth.from(date).minusMonths(dom==1&&hhmm<=1199?2:1);
            for(int i=0;i<3;i++)assertThat(p.balances().get(i).month()).isEqualTo(last.minusMonths(2-i).atDay(1));
        }
    }
    @Test void malformedVersionBoundsPresenceTruncationTrailingClockAndWrongImageReject() {
        byte[] valid=payload(List.of(1),0,null,0);
        for(int position:new int[]{0,1,2,17}){byte[] bad=valid.clone();bad[position]=(byte)255;assertThatThrownBy(()->L.decode(bad,0x07F41A8D)).isInstanceOf(IllegalArgumentException.class);}
        assertThatThrownBy(()->L.decode(Arrays.copyOf(valid,valid.length-1),0x07F41A8D)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->L.decode(Arrays.copyOf(valid,valid.length+1),0x07F41A8D)).isInstanceOf(IllegalArgumentException.class);
        for(int clock:new int[]{0,1900<<16|1,2036<<16|511})assertThatThrownBy(()->L.decode(valid,clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ClubFinancialHistoryLayout.values(null,null,ClubFinancialLedgerLayout.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialHistoryLayout.values(HexFormat.of().formatHex(valid),0x07F41A8D,"wrong-image")).isEmpty();
    }
}
