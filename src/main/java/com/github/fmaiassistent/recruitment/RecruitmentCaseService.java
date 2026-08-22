package com.github.fmaiassistent.recruitment;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.RecruitmentCaseEntity;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.repository.PlayerRepository;
import com.github.fmaiassistent.repository.RecruitmentCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RecruitmentCaseService {
    private static final int DEFAULT_VALID_DAYS = 30;
    private static final Set<String> INTEREST = Set.of("unknown", "interested", "not_interested");
    private static final Set<String> STAGES = Set.of(
            "monitoring", "bid_submitted", "bid_accepted", "bid_rejected",
            "contract_negotiation", "contract_agreed", "contract_rejected",
            "joining_other_club", "joined", "dismissed");
    private static final Set<String> SOURCES = Set.of("user", "agent_enquiry", "game", "heuristic");

    private final RecruitmentCaseRepository cases;
    private final PlayerRepository players;
    private final LoadMetadataRepository metadata;
    private final ManagedClubContextService managedClubs;

    public RecruitmentCaseService(
            RecruitmentCaseRepository cases,
            PlayerRepository players,
            LoadMetadataRepository metadata,
            ManagedClubContextService managedClubs) {
        this.cases = cases;
        this.players = players;
        this.metadata = metadata;
        this.managedClubs = managedClubs;
    }

    @Transactional
    public Map<String, Object> update(
            Long playerUniqueId,
            String interestStatus,
            String dealStage,
            Long quotedFee,
            Long quotedWeeklyWage,
            String note,
            String source,
            String observedGameDate) {
        return update(playerUniqueId, interestStatus, dealStage, quotedFee, quotedWeeklyWage,
                note, source, observedGameDate, null);
    }

    @Transactional
    public Map<String, Object> update(
            Long playerUniqueId,
            String interestStatus,
            String dealStage,
            Long quotedFee,
            Long quotedWeeklyWage,
            String note,
            String source,
            String observedGameDate,
            String validUntilGameDate) {
        if (playerUniqueId == null || playerUniqueId <= 0) {
            throw new IllegalArgumentException("playerUniqueId must be a positive FM Unique ID");
        }
        PlayerEntity player = players.findFirstByUniqueId(playerUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("player not found for UNIQUE_ID: " + playerUniqueId));
        String careerKey = currentCareerKey();
        if (blank(careerKey)) {
            throw new IllegalStateException(
                    "current career identity is unavailable; refresh FM26 data before recording evidence");
        }
        RecruitmentCaseEntity entity = cases.findByIdCareerKeyAndIdPlayerUniqueId(careerKey, playerUniqueId)
                .orElseGet(() -> new RecruitmentCaseEntity(careerKey, playerUniqueId));
        String safeInterest = enumValue(interestStatus, INTEREST,
                defaultText(entity.getInterestStatus(), "unknown"), "interestStatus");
        String safeStage = enumValue(dealStage, STAGES,
                defaultText(entity.getDealStage(), "monitoring"), "dealStage");
        String safeSource = enumValue(source, SOURCES,
                defaultText(entity.getSource(), "user"), "source");

        LocalDate observed = blank(observedGameDate)
                ? requireDate(currentGameDate(), "loaded game date")
                : requireDate(observedGameDate.trim(), "observedGameDate");
        LocalDate validUntil = blank(validUntilGameDate)
                ? observed.plusDays(DEFAULT_VALID_DAYS)
                : requireDate(validUntilGameDate.trim(), "validUntilGameDate");
        if (validUntil.isBefore(observed)) {
            throw new IllegalArgumentException("validUntilGameDate cannot be before observedGameDate");
        }

        entity.update(safeInterest, safeStage,
                quotedFee == null ? entity.getQuotedFee() : nonNegative(quotedFee),
                quotedWeeklyWage == null ? entity.getQuotedWeeklyWage() : nonNegative(quotedWeeklyWage),
                note == null ? entity.getNote() : trimTo(note, 2048),
                safeSource, observed.toString(), validUntil.toString());
        cases.save(entity);
        return toMap(entity, player, careerKey, currentGameDate());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> board() {
        String careerKey = currentCareerKey();
        String gameDate = currentGameDate();
        return cases.findAll().stream()
                .sorted(Comparator.comparing(RecruitmentCaseEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entity -> toMap(entity,
                        players.findFirstByUniqueId(entity.getPlayerUniqueId()).orElse(null),
                        careerKey, gameDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public RecruitmentCaseEntity find(Long playerUniqueId) {
        String careerKey = currentCareerKey();
        if (playerUniqueId == null || blank(careerKey)) return null;
        return cases.findByIdCareerKeyAndIdPlayerUniqueId(careerKey, playerUniqueId)
                .filter(entity -> effectiveness(entity, careerKey, currentGameDate()).effective())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<Long, RecruitmentCaseEntity> byPlayerUniqueId() {
        String careerKey = currentCareerKey();
        String gameDate = currentGameDate();
        if (blank(careerKey)) return Map.of();
        return cases.findByIdCareerKey(careerKey).stream()
                .filter(entity -> effectiveness(entity, careerKey, gameDate).effective())
                .collect(Collectors.toMap(RecruitmentCaseEntity::getPlayerUniqueId, Function.identity()));
    }

    public static boolean excludesCandidate(RecruitmentCaseEntity entity) {
        if (entity == null) return false;
        return "not_interested".equals(entity.getInterestStatus())
                || Set.of("contract_rejected", "joining_other_club", "joined", "dismissed")
                .contains(entity.getDealStage());
    }

    public static boolean verifiedInterest(RecruitmentCaseEntity entity) {
        return entity != null && "interested".equals(entity.getInterestStatus())
                && !"heuristic".equals(entity.getSource());
    }

    private String currentCareerKey() { return managedClubs.currentCareerKey(); }
    private String currentGameDate() {
        return metadata.findById("game_date").map(row -> row.getValue()).orElse(null);
    }

    private static Map<String, Object> toMap(
            RecruitmentCaseEntity entity,
            PlayerEntity player,
            String currentCareerKey,
            String currentGameDate) {
        Effectiveness effectiveness = effectiveness(entity, currentCareerKey, currentGameDate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("career_key", entity.getCareerKey());
        out.put("player_unique_id", entity.getPlayerUniqueId());
        out.put("name", player == null ? null : player.getName());
        out.put("club", player == null ? null : player.getClub());
        out.put("interest_status", entity.getInterestStatus());
        out.put("deal_stage", entity.getDealStage());
        out.put("quoted_fee", entity.getQuotedFee());
        out.put("quoted_weekly_wage", entity.getQuotedWeeklyWage());
        out.put("note", entity.getNote());
        out.put("source", entity.getSource());
        out.put("observed_game_date", entity.getObservedGameDate());
        out.put("valid_until_game_date", entity.getValidUntilGameDate());
        out.put("updated_at", entity.getUpdatedAt());
        out.put("effective", effectiveness.effective());
        out.put("effective_reason", effectiveness.reason());
        out.put("stale", !effectiveness.effective());
        return out;
    }

    private static Effectiveness effectiveness(
            RecruitmentCaseEntity entity,
            String currentCareerKey,
            String currentGameDate) {
        if (blank(currentCareerKey)) return new Effectiveness(false, "current_career_unavailable");
        if ("legacy".equals(entity.getCareerKey())) return new Effectiveness(false, "legacy_unscoped");
        if (!currentCareerKey.equals(entity.getCareerKey())) return new Effectiveness(false, "different_career");
        LocalDate current = date(currentGameDate);
        LocalDate observed = date(entity.getObservedGameDate());
        LocalDate validUntil = date(entity.getValidUntilGameDate());
        if (current == null) return new Effectiveness(false, "current_game_date_unavailable");
        if (observed == null || validUntil == null) return new Effectiveness(false, "invalid_or_missing_validity_date");
        if (current.isBefore(observed)) return new Effectiveness(false, "save_date_before_observation");
        if (current.isAfter(validUntil)) return new Effectiveness(false, "expired");
        return new Effectiveness(true, "current_career_and_within_validity");
    }

    private static LocalDate requireDate(String raw, String field) {
        LocalDate parsed = date(raw);
        if (parsed == null) throw new IllegalArgumentException(field + " must be an ISO date in YYYY-MM-DD format");
        return parsed;
    }

    private static LocalDate date(String raw) {
        try { return blank(raw) ? null : LocalDate.parse(raw); }
        catch (DateTimeParseException exception) { return null; }
    }

    private static String enumValue(String raw, Set<String> supported, String fallback, String field) {
        if (blank(raw)) return fallback;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!supported.contains(normalized)) throw new IllegalArgumentException(field + " must be one of " + supported);
        return normalized;
    }

    private static Long nonNegative(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("quoted money values cannot be negative");
        return value;
    }

    private static String trimTo(String value, int maximum) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maximum ? trimmed : trimmed.substring(0, maximum);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String defaultText(String value, String fallback) { return blank(value) ? fallback : value; }

    private record Effectiveness(boolean effective, String reason) {
    }
}
