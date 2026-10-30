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
                .putLong(0x7FFFD5260000L+0x4CDA1F8).putInt(0).putInt(630).array();
        var club=new NativeClubSnapshot(630,0,"CLUB","Chelsea",0x10000000L,HexFormat.of().formatHex(prefix),raw,1684);
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(club),ClubUefaCoefficientLayout.FM27_MODULE_SHA256));
        var service=new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),
                mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,mock(CompetitionNameRepository.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs",Long.class)).isZero();
        var saved=nativeClubs.findById(630L).orElseThrow();
        assertThat(saved.getUefaCoefficientHex()).isEqualTo(raw);
        assertThat(saved.getUefaRuleVersion()).isEqualTo(1684);
        assertThat(saved.getModuleSha256()).isEqualTo(ClubUefaCoefficientLayout.FM27_MODULE_SHA256);
        assertThat(service.countCoefficientClubs()).isEqualTo(1);
        assertThat(service.findCoefficientClubs(PageRequest.of(0,10))).hasSize(1);
        assertThat(service.findCoefficientClubs(" CHELSEA ",PageRequest.of(0,10))).hasSize(1);
        assertThat(service.countCoefficientClubs("no such club")).isZero();
        var values=service.findClubCoefficients(630L).orElseThrow().coefficients();
        assertThat(values.currentSeasonPoints()).isEqualByComparingTo("0");
        assertThat(values.fiveYearRank()).isEqualTo(12);assertThat(values.history()).hasSize(10);
        assertThat(service.findClubCoefficients(null)).isEmpty();
        assertThat(service.findClubCoefficients(999L)).isEmpty();
        var incomplete=new NativeClubSnapshot(630,0,"CLUB",null,club.sourceAddress(),club.prefixHex(),raw,null);
        assertThatThrownBy(()->writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(incomplete),
                ClubUefaCoefficientLayout.FM27_MODULE_SHA256))).isInstanceOf(IllegalArgumentException.class);
    }

    private static String captured()throws Exception {
        try(var input=Files.newInputStream(Path.of("src/test/resources/fm27/clubs-captured.json"))) {
            byte[] bytes=input.readNBytes(131073);assertThat(bytes.length).isLessThanOrEqualTo(131072);
            var capture=tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);
            for(var sample:capture.path("samples"))if(sample.path("uid").asLong()==630)return sample.path("coefficientRaw").asString();
            throw new AssertionError("Missing captured FM27 Chelsea coefficients");
        }
    }
}
