package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.memory.ClubUefaCoefficientLayout;
import com.github.fmaiassistent.service.ClubDatabaseService;
import com.github.fmaiassistent.service.CompetitionDatabaseService;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import(SnapshotDatabaseWriter.class)
class ClubUefaCoefficientPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired NativeClubRepository nativeClubs;
    @Autowired JdbcTemplate jdbc;

    @Test void canonicalOnlyClubRetainsAllRawBytesAndIsBrowsableWithoutAContextualView() throws Exception {
        var raw=captured();var prefix=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0x7FFAA0800000L+0x44BA518).putInt(0).putInt(1036).array();
        var club=new NativeClubSnapshot(1036,0,"CLUB","sc Heerenveen",0x10000000L,HexFormat.of().formatHex(prefix),raw,1639);
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(club),ClubUefaCoefficientLayout.MODULE_SHA256));
        var service=new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),
                mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,mock(CompetitionNameRepository.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs",Long.class)).isZero();
        var saved=nativeClubs.findById(1036L).orElseThrow();
        assertThat(saved.getUefaCoefficientHex()).isEqualTo(raw);
        assertThat(saved.getUefaRuleVersion()).isEqualTo(1639);
        assertThat(saved.getModuleSha256()).isEqualTo(ClubUefaCoefficientLayout.MODULE_SHA256);
        assertThat(service.countCoefficientClubs()).isEqualTo(1);
        assertThat(service.findCoefficientClubs(PageRequest.of(0,10))).hasSize(1);
        assertThat(service.findCoefficientClubs(" HEERENVEEN ",PageRequest.of(0,10))).hasSize(1);
        assertThat(service.countCoefficientClubs("no such club")).isZero();
        var values=service.findClubCoefficients(1036L).orElseThrow().coefficients();
        assertThat(values.currentSeasonPoints()).isEqualByComparingTo("37");
        assertThat(values.fiveYearRank()).isEqualTo(16);assertThat(values.history()).hasSize(10);
        assertThat(service.findClubCoefficients(null)).isEmpty();
        assertThat(service.findClubCoefficients(999L)).isEmpty();
        var incomplete=new NativeClubSnapshot(1036,0,"CLUB",null,club.sourceAddress(),club.prefixHex(),raw,null);
        assertThatThrownBy(()->writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(incomplete),
                ClubUefaCoefficientLayout.MODULE_SHA256))).isInstanceOf(IllegalArgumentException.class);
    }

    private static String captured()throws Exception {
        byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_uefa_coefficients_population_live_20261026.csv"))){b=in.readNBytes(8*1024*1024+1);}
        assertThat(b.length).isLessThanOrEqualTo(8*1024*1024);
        return new String(b,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(l->l.split(",",-1))
                .filter(r->r[1].equals("1036")).findFirst().orElseThrow()[6];
    }
}
