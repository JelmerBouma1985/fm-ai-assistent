package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.player.PlayerColumnNames;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Database predicates for the immutable FM snapshot catalog. */
public final class CatalogSpecifications {
    private static final Set<String> PLAYER_NUMERIC_FIELDS = numericFields(PlayerEntity.class);
    private static final Set<String> STAFF_NUMERIC_FIELDS = numericFields(StaffEntity.class);

    private CatalogSpecifications() {
    }

    public static Specification<PlayerEntity> players(PlayerFilterCriteria criteria) {
        PlayerFilterCriteria filter = criteria == null ? PlayerFilterCriteria.empty() : criteria;
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            PlayerJoins joins = playerJoins(root, query.getResultType() == Long.class);
            contains(builder, root.get("name"), filter.name(), predicates);
            equalIgnoreCase(builder, root.get("gender"), filter.gender(), predicates);
            equalIgnoreCase(builder, joins.playingCompetition().get("nation"), filter.playingNation(), predicates);
            equalIgnoreCase(builder, joins.playingCompetition().get("name"), filter.playingCompetition(), predicates);
            club(builder, root, joins.registeredClub(), joins.playingClub(), filter.club(), predicates);
            equalIgnoreCase(builder, root.get("nationality"), filter.nationality(), predicates);
            integerRange(builder, root.get("age"), filter.ageMin(), filter.ageMax(), predicates);
            integerRange(builder, root.get("heightCm"), filter.heightMin(), filter.heightMax(), predicates);
            integerRange(builder, root.get("currentReputation"), filter.currentReputationMin(),
                    filter.currentReputationMax(), predicates);
            integerRange(builder, root.get("homeReputation"), filter.homeReputationMin(),
                    filter.homeReputationMax(), predicates);
            integerRange(builder, root.get("worldReputation"), filter.worldReputationMin(),
                    filter.worldReputationMax(), predicates);
            integerRange(builder, root.get("ca"), filter.caMin(), filter.caMax(), predicates);
            integerRange(builder, root.get("pa"), filter.paMin(), filter.paMax(), predicates);
            longRange(builder, root.get("askingPrice"), filter.askingPriceMin(), filter.askingPriceMax(), predicates);
            if (filter.salaryMax() != null) {
                predicates.add(builder.le(root.get("salaryWeeklyRaw").as(Long.class), filter.salaryMax()));
            }
            isoDateRange(builder, root.get("contractEndDate"), filter.contractEndDateFrom(),
                    filter.contractEndDateTo(), predicates);
            numericMinimums(builder, root, filter.positionMinimums(), PLAYER_NUMERIC_FIELDS, predicates);
            numericMinimums(builder, root, filter.attributeMinimums(), PLAYER_NUMERIC_FIELDS, predicates);
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    @SuppressWarnings("unchecked")
    private static PlayerJoins playerJoins(Root<PlayerEntity> root, boolean countQuery) {
        if (countQuery) {
            Join<PlayerEntity, ClubEntity> registeredClub = root.join("clubEntity", JoinType.LEFT);
            Join<PlayerEntity, ClubEntity> playingClub = root.join("playingClubEntity", JoinType.LEFT);
            return new PlayerJoins(
                    registeredClub,
                    playingClub,
                    playingClub.join("competitionEntity", JoinType.LEFT));
        }
        Fetch<PlayerEntity, ClubEntity> registeredClub = root.fetch("clubEntity", JoinType.LEFT);
        Fetch<PlayerEntity, ClubEntity> playingClub = root.fetch("playingClubEntity", JoinType.LEFT);
        Fetch<ClubEntity, CompetitionEntity> playingCompetition =
                playingClub.fetch("competitionEntity", JoinType.LEFT);
        return new PlayerJoins(
                (Join<PlayerEntity, ClubEntity>) registeredClub,
                (Join<PlayerEntity, ClubEntity>) playingClub,
                (Join<ClubEntity, CompetitionEntity>) playingCompetition);
    }

    public static Specification<StaffEntity> staff(StaffFilterCriteria criteria) {
        StaffFilterCriteria filter = criteria == null ? StaffFilterCriteria.empty() : criteria;
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            contains(builder, root.get("name"), filter.name(), predicates);
            equalIgnoreCase(builder, root.get("gender"), filter.gender(), predicates);
            equalIgnoreCase(builder, root.get("nationality"), filter.nationality(), predicates);
            equalIgnoreCase(builder, root.get("club"), filter.club(), predicates);
            equalIgnoreCase(builder, root.get("division"), filter.division(), predicates);
            equalIgnoreCase(builder, root.get("job"), filter.job(), predicates);
            integerRange(builder, root.get("age"), filter.ageMin(), filter.ageMax(), predicates);
            integerRange(builder, root.get("ca"), filter.caMin(), filter.caMax(), predicates);
            integerRange(builder, root.get("pa"), filter.paMin(), filter.paMax(), predicates);
            integerRange(builder, root.get("currentReputation"), filter.currentReputationMin(), null, predicates);
            integerRange(builder, root.get("worldReputation"), filter.worldReputationMin(), null, predicates);
            if (filter.salaryWeeklyMax() != null) {
                predicates.add(builder.le(root.<Long>get("salaryWeeklyRaw"), filter.salaryWeeklyMax()));
            }
            isoDateRange(builder, root.get("contractEndDate"), filter.contractEndDateFrom(),
                    filter.contractEndDateTo(), predicates);
            numericMinimums(builder, root, filter.attributeMinimums(), STAFF_NUMERIC_FIELDS, predicates);
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<ClubEntity> clubs(ClubFilterCriteria criteria) {
        ClubFilterCriteria filter = criteria == null ? ClubFilterCriteria.empty() : criteria;
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIgnoreCase(builder, root.get("name"), filter.name(), predicates);
            equalIgnoreCase(builder, root.get("competition"), filter.competition(), predicates);
            equalIgnoreCase(builder, root.get("nation"), filter.nation(), predicates);
            integerRange(builder, root.get("reputation"), filter.reputationMin(), filter.reputationMax(), predicates);
            longRange(builder, root.get("balance"), filter.balanceMin(), filter.balanceMax(), predicates);
            longRange(builder, root.get("transferBudget"), filter.transferBudgetMin(),
                    filter.transferBudgetMax(), predicates);
            longRange(builder, root.get("payrollBudget"), filter.payrollBudgetMin(),
                    filter.payrollBudgetMax(), predicates);
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<CompetitionEntity> competitions(CompetitionFilterCriteria criteria) {
        CompetitionFilterCriteria filter = criteria == null ? CompetitionFilterCriteria.empty() : criteria;
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIgnoreCase(builder, root.get("name"), filter.name(), predicates);
            equalIgnoreCase(builder, root.get("nation"), filter.nation(), predicates);
            equalIgnoreCase(builder, root.get("gender"), filter.gender(), predicates);
            integerRange(builder, root.get("reputation"), filter.reputationMin(),
                    filter.reputationMax(), predicates);
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void club(
            CriteriaBuilder builder,
            Root<PlayerEntity> root,
            Join<PlayerEntity, ClubEntity> registeredClub,
            Join<PlayerEntity, ClubEntity> playingClub,
            String value,
            List<Predicate> predicates) {
        if (blank(value)) {
            return;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        predicates.add(builder.or(
                builder.equal(builder.lower(root.get("club")), normalized),
                builder.equal(builder.lower(root.get("playingClub")), normalized),
                builder.equal(builder.lower(registeredClub.get("name")), normalized),
                builder.equal(builder.lower(playingClub.get("name")), normalized)));
    }

    private static void contains(
            CriteriaBuilder builder,
            Expression<String> field,
            String value,
            List<Predicate> predicates) {
        if (!blank(value)) {
            String pattern = "%" + escapeLike(value.strip().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(builder.like(builder.lower(field), pattern, '\\'));
        }
    }

    private static void equalIgnoreCase(
            CriteriaBuilder builder,
            Expression<String> field,
            String value,
            List<Predicate> predicates) {
        if (!blank(value)) {
            predicates.add(builder.equal(builder.lower(field), value.strip().toLowerCase(Locale.ROOT)));
        }
    }

    private static void integerRange(
            CriteriaBuilder builder,
            Expression<Integer> field,
            Integer minimum,
            Integer maximum,
            List<Predicate> predicates) {
        if (minimum != null) {
            predicates.add(builder.greaterThanOrEqualTo(field, minimum));
        }
        if (maximum != null) {
            predicates.add(builder.lessThanOrEqualTo(field, maximum));
        }
    }

    private static void longRange(
            CriteriaBuilder builder,
            Expression<Long> field,
            Long minimum,
            Long maximum,
            List<Predicate> predicates) {
        if (minimum != null) {
            predicates.add(builder.greaterThanOrEqualTo(field, minimum));
        }
        if (maximum != null) {
            predicates.add(builder.lessThanOrEqualTo(field, maximum));
        }
    }

    private static void isoDateRange(
            CriteriaBuilder builder,
            Path<String> field,
            LocalDate from,
            LocalDate to,
            List<Predicate> predicates) {
        if (from != null) {
            predicates.add(builder.greaterThanOrEqualTo(field, from.toString()));
        }
        if (to != null) {
            predicates.add(builder.lessThanOrEqualTo(field, to.toString()));
        }
    }

    private static void numericMinimums(
            CriteriaBuilder builder,
            Root<?> root,
            Map<String, Integer> minimums,
            Set<String> allowedFields,
            List<Predicate> predicates) {
        for (Map.Entry<String, Integer> minimum : minimums.entrySet()) {
            String field = PlayerColumnNames.toEntityFieldName(minimum.getKey().toLowerCase(Locale.ROOT));
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unsupported numeric catalog field: " + minimum.getKey());
            }
            predicates.add(builder.ge(root.<Number>get(field), minimum.getValue()));
        }
    }

    private static Set<String> numericFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> Number.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record PlayerJoins(
            Join<PlayerEntity, ClubEntity> registeredClub,
            Join<PlayerEntity, ClubEntity> playingClub,
            Join<ClubEntity, CompetitionEntity> playingCompetition) {
    }
}
