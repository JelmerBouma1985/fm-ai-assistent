package com.github.fmaiassistent.domain.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClubEntityTest {
    @Test
    void mapsFacilityRatingsFromRamExportToEntityAndApi() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "sc Heerenveen");
        row.put("trainingFacilities", 18);
        row.put("youthFacilities", 13);
        row.put("youthCoaching", 20);
        row.put("youthRecruitment", 17);
        row.put("corporateFacilities", 9);

        ClubEntity club = ClubEntity.fromExportRow(row);

        assertThat(club.getTrainingFacilities()).isEqualTo(18);
        assertThat(club.getYouthFacilities()).isEqualTo(13);
        assertThat(club.getYouthCoaching()).isEqualTo(20);
        assertThat(club.getYouthRecruitment()).isEqualTo(17);
        assertThat(club.getCorporateFacilities()).isEqualTo(9);
        assertThat(club.toApiMap()).containsEntry("TRAINING_FACILITIES", 18)
                .containsEntry("YOUTH_FACILITIES", 13)
                .containsEntry("YOUTH_COACHING", 20)
                .containsEntry("YOUTH_RECRUITMENT", 17)
                .containsEntry("CORPORATE_FACILITIES", 9);
    }
}
