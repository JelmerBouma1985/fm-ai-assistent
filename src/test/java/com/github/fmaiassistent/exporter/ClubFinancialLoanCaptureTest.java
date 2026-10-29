package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

public class ClubFinancialLoanCaptureTest {
    public static final long BASE=0x7FFA9D220000L;
    public static final int CLOCK=0x07F41A8D;
    static final ClubFinancialLoanLayout L=ClubFinancialLoanLayout.current();

    @Test void all26377OrdinaryOwnersAnd477RecordsIncludingUnknownArePreserved()throws Exception {
        var f=fixture();var capture=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,L);
        assertThat(capture.records()).hasSize(26377);int count=0,owners=0,unknown=0;
        for(var record:capture.records()) {
            String[] row=f.rows.get(record.uniqueId());String expected=payload(row);
            assertThat(record.financialLoansHex()).isEqualTo(expected);assertThat(record.financialLoanClock()).isEqualTo(CLOCK);
            var values=ClubFinancialLoanLayout.values(expected,CLOCK,L.MODULE_SHA256).orElseThrow();
            if(!values.loans().isEmpty())owners++;count+=values.loans().size();
            for(var loan:values.loans())if(loan.kind()==255){unknown++;assertThat(loan.typeName()).isEqualTo("Unknown loan type (255)");assertThat(loan.remainingPrincipal()).isNull();}
        }
        assertThat(count).isEqualTo(477);assertThat(owners).isEqualTo(228);assertThat(unknown).isEqualTo(1);
        byte[] expectedBytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_loan_constructor_and_labels_verified_20261026.json"))){expectedBytes=in.readNBytes(8*1024*1024+1);}
        assertThat(expectedBytes.length).isLessThanOrEqualTo(8*1024*1024);
        var expectedByOwner=new HashMap<Long,List<tools.jackson.databind.JsonNode>>();
        for(var node:new tools.jackson.databind.json.JsonMapper().readTree(expectedBytes).path("decoded"))
            expectedByOwner.computeIfAbsent(node.path("clubUid").asLong(),ignored->new ArrayList<>()).add(node);
        for(var record:capture.records()) {
            var loans=ClubFinancialLoanLayout.values(record.financialLoansHex(),record.financialLoanClock(),L.MODULE_SHA256).orElseThrow().loans();
            var expected=expectedByOwner.getOrDefault(record.uniqueId(),List.of());assertThat(loans).hasSize(expected.size());
            for(int i=0;i<loans.size();i++){var actual=loans.get(i);var e=expected.get(i);assertThat(actual.rawHex()).isEqualTo(e.path("rawHex").asText());
                assertThat(actual.remainingPrincipal()).isEqualTo(e.path("remainingPrincipal").isMissingNode()||e.path("remainingPrincipal").isNull()?null:e.path("remainingPrincipal").asInt());
                assertThat(actual.remainingInterest()).isEqualTo(e.path("remainingInterest").isMissingNode()||e.path("remainingInterest").isNull()?null:e.path("remainingInterest").asInt());}
        }
        var one=capture.records().stream().filter(r->r.uniqueId()==1036).findFirst().orElseThrow();
        var loan=ClubFinancialLoanLayout.values(one.financialLoansHex(),one.financialLoanClock(),L.MODULE_SHA256).orElseThrow().loans().getFirst();
        assertThat(loan.originalPrincipal()).isEqualTo(94556070);assertThat(loan.scheduledPayment()).isEqualTo(374998);
        assertThat(loan.interestComponent()).isEqualTo(59812);assertThat(loan.remainingPrincipal()).isEqualTo(72807966);
        assertThat(loan.remainingInterest()).isEqualTo(13816572);assertThat(loan.maturity()).hasToString("2055-05-18");
        assertThat(loan.repaymentsStart()).hasToString("2030-05-24");capture.verify();
    }

    @Test void everyShiftedLayoutRejectsSameCapturedFixtureAndFinalPublicationChecksEveryLayer()throws Exception {
        var f=fixture();var components=ClubFinancialLoanLayout.class.getRecordComponents();
        var types=Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        var constructor=ClubFinancialLoanLayout.class.getDeclaredConstructor(types);
        for(int i=0;i<components.length;i++) {
            Object[] args=new Object[components.length];for(int j=0;j<args.length;j++)args[j]=components[j].getAccessor().invoke(L);
            if(types[i]==long.class)args[i]=((Long)args[i])+1;else args[i]=((Integer)args[i])+1;
            var shifted=constructor.newInstance(args);
            assertThatThrownBy(()->new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,shifted))
                    .as(components[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
        }
        var capture=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,L);var row=f.rows.get(1036L);
        long club=hex(row[2]),data=hex(row[4]),vector=hex(row[6]),record=hex(row[8].split(":")[0]);
        for(long a:new long[]{BASE+L.clockRva(),club+L.clubDataRel(),data+L.vectorRel(),vector,record}) {
            byte[] previous=f.blocks.get(a),changed=previous.clone();changed[changed.length-1]^=1;f.put(a,changed);
            assertThatThrownBy(capture::verify).isInstanceOf(IOException.class).hasMessageContaining("changed");f.put(a,previous);
        }
        capture.verify();
    }

    @Test void corruptBoundsAndDuplicateMembersAbortInsteadOfDroppingRecords()throws Exception {
        var f=fixture();var row=f.rows.get(1036L);long data=hex(row[4]);
        f.put(data+L.vectorRel(),longs(hex(row[6]),hex(row[6])+513*8,hex(row[6])+513*8));
        var badBounds=f;assertThatThrownBy(()->new ClubIdentitySnapshot(badBounds,BASE,ClubIdentityLayout.current(),null,L)).isInstanceOf(IOException.class).hasMessageContaining("range anchor");
        f=fixture();var multiple=f.rows.values().stream().filter(r->r[8].contains(";")).findFirst().orElseThrow();
        byte[] slots=f.blocks.get(hex(multiple[6])).clone();System.arraycopy(slots,0,slots,8,8);f.put(hex(multiple[6]),slots);
        var duplicate=f;assertThatThrownBy(()->new ClubIdentitySnapshot(duplicate,BASE,ClubIdentityLayout.current(),null,L)).isInstanceOf(IOException.class).hasMessageContaining("Duplicate");
    }

    public static List<String[]> rows()throws Exception {
        byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_financial_commitment_population_fm620_20261026.csv"))){b=in.readNBytes(8*1024*1024+1);}
        assertThat(b.length).isLessThanOrEqualTo(8*1024*1024);
        return new String(b,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(s->s.split(",",-1)).toList();
    }
    public static String payload(String[] row){return row[8].isEmpty()?"":Arrays.stream(row[8].split(";")).map(e->e.split(":")[1]).collect(java.util.stream.Collectors.joining());}
    static ClubUefaCoefficientCaptureTest.Blocks fixture()throws Exception {
        var f=new ClubUefaCoefficientCaptureTest.Blocks();var table=ByteBuffer.allocate(26839*8).order(ByteOrder.LITTLE_ENDIAN);
        for(var r:rows()) {
            long club=hex(r[2]),data=hex(r[4]);int index=Integer.parseInt(r[0]);f.rows.put(Long.parseLong(r[1]),r);
            table.putLong(index*8,club);f.put(club,HexFormat.of().parseHex(r[3]));f.put(club+0xC0,new byte[16]);
            f.put(club+L.clubDataRel(),longs(data));f.put(data+L.vectorRel(),HexFormat.of().parseHex(r[5]));
            if(!r[7].isEmpty())f.put(hex(r[6]),HexFormat.of().parseHex(r[7]));
            if(!r[8].isEmpty())for(String e:r[8].split(";")){var a=e.split(":");f.put(hex(a[0]),HexFormat.of().parseHex(a[1]));}
        }
        f.put(BASE+ClubIdentityLayout.current().registryRva(),longs(ClubUefaCoefficientCaptureTest.DESCRIPTOR));
        f.put(ClubUefaCoefficientCaptureTest.DESCRIPTOR+0x80,longs(ClubUefaCoefficientCaptureTest.HEADER));
        f.put(ClubUefaCoefficientCaptureTest.HEADER,longs(ClubUefaCoefficientCaptureTest.TABLE,ClubUefaCoefficientCaptureTest.TABLE+table.capacity(),ClubUefaCoefficientCaptureTest.TABLE+table.capacity()));
        f.put(ClubUefaCoefficientCaptureTest.TABLE,table.array());f.put(BASE+L.clockRva(),ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(CLOCK).array());return f;
    }
    static long hex(String s){return Long.parseUnsignedLong(s,16);}
    static byte[] longs(long...v){var b=ByteBuffer.allocate(v.length*8).order(ByteOrder.LITTLE_ENDIAN);for(long x:v)b.putLong(x);return b.array();}
}
