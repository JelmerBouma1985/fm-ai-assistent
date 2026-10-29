package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ClubCurrentWagesCapturedTest {
    @Test void all680CapturedFullObjectsDecodeTheNativeBonusInclusiveCache()throws Exception {
        for(var row:rows()) {
            var f=fixture(row);var decoded=new ClubExporter().readFinance(f.memory,f.club,f.base);
            var tail=ByteBuffer.wrap(HexFormat.of().parseHex(row[6])).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(decoded.currentWagesIncludingBonuses()).as("UID "+row[1]).isEqualTo((long)tail.getInt(0x81C-0x7C0));
            if(row[1].equals("1036"))assertThat(decoded.currentWagesIncludingBonuses()).isEqualTo(1_763_603L);
            if(row[1].equals("2000077838"))assertThat(decoded.currentWagesIncludingBonuses()).isEqualTo(739L);
        }
    }

    @Test void lazyInvalidCachesStayUnavailableAndZeroRemainsAValueWithoutCallingNativeCode()throws Exception {
        var row=rows().stream().filter(r->r[1].equals("1036")).findFirst().orElseThrow();
        var f=fixture(row);var layout=ClubFinanceLayout.current();
        for(int cached:new int[]{Integer.MIN_VALUE,-1001,-1000,0,1763603}) {
            ByteBuffer.wrap(f.memory.object).order(ByteOrder.LITTLE_ENDIAN).putInt(layout.currentWagesRel(),cached);
            var value=new ClubExporter().readFinance(f.memory,f.club,f.base).currentWagesIncludingBonuses();
            if(cached<=-1001)assertThat(value).isNull();else assertThat(value).isEqualTo((long)cached);
        }
        f.memory.pointer=0;assertThat(new ClubExporter().readFinance(f.memory,f.club,f.base).currentWagesIncludingBonuses()).isNull();
    }

    private record Fixture(Memory memory,long club,long base) { }
    private static Fixture fixture(String[] r) {
        long club=Long.parseUnsignedLong(r[2],16),finance=Long.parseUnsignedLong(r[4],16);
        // Only prefix64 and tail7C0..8C0 are captured. Unused intervening bytes are synthetic zeroes.
        byte[] object=new byte[0x8C0];System.arraycopy(HexFormat.of().parseHex(r[5]),0,object,0,64);
        System.arraycopy(HexFormat.of().parseHex(r[6]),0,object,0x7C0,256);
        return new Fixture(new Memory(club,finance,object),club,0x7FFAA0800000L);
    }
    static List<String[]> rows()throws Exception {
        byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_finance_payroll_population_20261026.csv"))){b=in.readNBytes(1_048_577);}
        assertThat(b.length).isLessThanOrEqualTo(1_048_576);var rows=new String(b,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(l->l.split(",",-1)).toList();assertThat(rows).hasSize(680);return rows;
    }
    private static class Memory implements ProcessMemoryReader {
        final long club,finance;final byte[] object;long pointer;
        Memory(long c,long f,byte[] o){club=c;finance=f;object=o;pointer=f;}
        public int pid(){return -1;}public List<MemoryRegion> maps(){return List.of();}public void close(){}
        public byte[] readBytes(long a,int n)throws IOException {
            if(a==club+ClubRecordLayout.current().clubFinanceBlockRel()&&n==8)return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(pointer).array();
            if(a<finance||a-finance+n>object.length)throw new IOException("Uncaptured fixture read");return Arrays.copyOfRange(object,(int)(a-finance),(int)(a-finance)+n);
        }
    }
}
