package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.StaffFilterCriteria;
import com.github.fmaiassistent.repository.StaffRepository;
import com.github.fmaiassistent.staff.StaffAttributeDefinitions;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffDatabaseServiceTest {
    @Test
    void everySelectedCoachingRoleMinimumMustBeMet() {
        StaffRepository repository = mock(StaffRepository.class);
        StaffEntity attackingOnly = staff("Attacking only", Map.of(
                "attacking", 20, "technical", 20, "authority", 20, "determination", 20, "motivating", 20));
        StaffEntity multiRole = staff("Multi-role", Map.of(
                "attacking", 20, "technical", 20, "goalkeeping", 20,
                "authority", 20, "determination", 20, "motivating", 20));
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<StaffEntity>>any()))
                .thenReturn(List.of(attackingOnly, multiRole));
        StaffDatabaseService service = new StaffDatabaseService(repository, mock(ClubRepository.class));
        StaffFilterCriteria criteria = new StaffFilterCriteria(
                "", "", "", "", "", "", null, null, null, null, null, null,
                null, null, null, null, null, Map.of(), "", null,
                Map.of("attacking_technical", 4.5, "goalkeeping", 4.5));

        assertThat(service.findStaff(criteria)).extracting(StaffEntity::getName)
                .containsExactly("Multi-role");
    }

    private static StaffEntity staff(String name, Map<String, Integer> overrides) {
        Map<String, Object> row = new HashMap<>();
        StaffAttributeDefinitions.ALL.forEach(attribute -> row.put(attribute.key(), 10));
        row.putAll(overrides);
        row.put("name", name);
        row.put("job", "Coach");
        row.put("unique_id", (long) name.hashCode() & 0xffffffffL);
        row.put("ca", 100);
        row.put("pa", 100);
        return StaffEntity.fromExportRow(row);
    }
}
