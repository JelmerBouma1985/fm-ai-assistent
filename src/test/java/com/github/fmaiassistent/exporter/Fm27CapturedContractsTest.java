package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Native typed owners plus retained production reader bytes; no live process is needed. */
public class Fm27CapturedContractsTest {
    @Test void allFourNativeTypesAndTheirTeamNamespacesSurviveTheFm27Profile() throws Exception {
        long[][] expected={{386,1688},{55,2000801215L},{215501,744},{12093983,1661}};
        String[][] kinds={{"FULL"},{"BASIC"},{"FULL","LOAN"},{"FULL","B_CLUB"}};
        var fixtures=fixtures();assertThat(fixtures.size()).isEqualTo(5);
        for(int i=0;i<4;i++) {
            var fixture=fixtures.get(i);assertThat(fixture.path("uid").asLong()).isEqualTo(expected[i][0]);
            var contracts=exporter(fixture,ContractRecordLayout.fm27()).read(fixture.path("person").asLong(),expected[i][0]);
            assertThat(contracts).extracting(com.github.fmaiassistent.domain.ContractSnapshot::kind).containsExactly(kinds[i]);
            assertThat(contracts.getFirst().teamUid()).isEqualTo(expected[i][1]);
            long owner=expected[i][0];
            String namespace=i==1?"NATIONAL_TEAM_CONTAINER":"CLUB";
            assertThat(contracts).allSatisfy(c->{assertThat(c.ownerUid()).isEqualTo(owner);assertThat(c.teamOwnerKind()).isEqualTo(namespace);});
            if(i==1)assertThat(contracts.getFirst().teamOwnerUid()).isEqualTo(784L);
        }
        var full=exporter(fixtures.get(0),ContractRecordLayout.fm27()).read(fixtures.get(0).path("person").asLong(),386);
        assertThat(full.getFirst().terms()).hasSize(7);
        assertThat(full.getFirst().terms()).anyMatch(t->t.competitionReferences()!=null);
    }

    @Test void actualSeparateFutureContractRetainsItsWageEndAndOfferIdentity() throws Exception {
        var f=fixtures().get(4);
        var future=exporter(f,ContractRecordLayout.fm27()).readFutureEmployment(f.path("person").asLong(),2000365091L,0).orElseThrow();
        assertThat(future.offerIndex()).isZero();assertThat(future.wageRaw()).isEqualTo(19821);
        assertThat(future.endDate()).isEqualTo(java.time.LocalDate.of(2030,6,30));
        assertThat(future.contract().ownerUid()).isEqualTo(2000365091L);
        for(var shifted:shifted(FutureEmploymentLayout.fm27()))
            assertThatThrownBy(()->exporter(f,ContractRecordLayout.fm27()).readFutureEmployment(f.path("person").asLong(),2000365091L,0,(FutureEmploymentLayout)shifted))
                    .isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
    }

    @Test void everyContractRootTypeAndFieldDisplacementRejectsTheSameNativeFixtureWhenShifted() throws Exception {
        var f=fixtures().get(0);var layout=ContractRecordLayout.fm27();
        var components=ContractRecordLayout.class.getRecordComponents();
        var ctor=ContractRecordLayout.class.getDeclaredConstructor(Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
        for(int i=0;i<components.length;i++) {
            Object value=components[i].getAccessor().invoke(layout);
            List<Record> variants=value instanceof Record r?shifted(r):List.of();
            for(var variant:variants) {
                Object[] args=new Object[components.length];for(int j=0;j<args.length;j++)args[j]=components[j].getAccessor().invoke(layout);
                args[i]=variant;var changed=(ContractRecordLayout)ctor.newInstance(args);
                assertThatThrownBy(()->exporter(f,changed).read(f.path("person").asLong(),386))
                        .as(components[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
            }
        }
        for(var shifted:shifted(FutureTransferLayout.fm27()))
            assertThatThrownBy(()->PlayerSnapshotValidator.validateFutureTransferLayout((FutureTransferLayout)shifted)).isInstanceOf(IOException.class);
        for(var shifted:shifted(AwardNameLayout.fm27()))
            assertThatThrownBy(()->PlayerSnapshotValidator.validateAwardNameLayout((AwardNameLayout)shifted)).isInstanceOf(IOException.class);
    }

    private static List<Record> shifted(Record original)throws Exception {
        var cs=original.getClass().getRecordComponents();var ctor=original.getClass().getDeclaredConstructor(Arrays.stream(cs).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
        var variants=new ArrayList<Record>();
        for(int i=0;i<cs.length;i++) {
            Object[] args=new Object[cs.length];for(int j=0;j<args.length;j++)args[j]=cs[j].getAccessor().invoke(original);
            if(args[i] instanceof Integer n)args[i]=n+1;else if(args[i] instanceof Long n)args[i]=n+8;else continue;
            variants.add((Record)ctor.newInstance(args));
        }
        return variants;
    }
    public static JsonNode fixtures()throws Exception {
        try(var in=Files.newInputStream(Path.of("src/test/resources/fm27/contracts-captured.json"))){
            byte[] bytes=in.readNBytes(262145);assertThat(bytes.length).isLessThanOrEqualTo(262144);
            return JsonMapper.builder().build().readTree(bytes);
        }
    }
    public static ContractExporter exporter(JsonNode fixture,ContractRecordLayout layout) {
        return new ContractExporter(new ProcessMemoryReader(){
            public int pid(){return 1;}public Platform platform(){return Platform.WINDOWS;}public void close(){}public List<MemoryRegion> maps(){return List.of();}
            public byte[] readBytes(long address,int count)throws IOException {
                var found=fixture.path("memory").get(address+":"+count);
                if(found==null)throw new IOException("Unmapped captured contract read");
                return HexFormat.of().parseHex(found.asString());
            }
        },fixture.path("base").asLong(),layout);
    }
}
