package com.github.fmaiassistent.recruitment;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.RecruitmentCaseEntity;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.repository.PlayerRepository;
import com.github.fmaiassistent.repository.RecruitmentCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final Set<String> INTEREST = Set.of("unknown", "interested", "not_interested");
    private static final Set<String> STAGES = Set.of(
            "monitoring", "bid_submitted", "bid_accepted", "bid_rejected",
            "contract_negotiation", "contract_agreed", "contract_rejected",
            "joining_other_club", "joined", "dismissed");
    private static final Set<String> SOURCES = Set.of("user", "agent_enquiry", "game", "heuristic");

    private final RecruitmentCaseRepository cases;
    private final PlayerRepository players;
    private final LoadMetadataRepository metadata;

    public RecruitmentCaseService(
            RecruitmentCaseRepository cases,
            PlayerRepository players,
            LoadMetadataRepository metadata) {
        this.cases = cases;
        this.players = players;
        this.metadata = metadata;
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
        if (playerUniqueId == null || playerUniqueId <= 0) {
            throw new IllegalArgumentException("playerUniqueId must be a positive FM Unique ID");
        }
        PlayerEntity player = players.findFirstByUniqueId(playerUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("player not found for UNIQUE_ID: " + playerUniqueId));
        RecruitmentCaseEntity entity = cases.findById(playerUniqueId)
                .orElseGet(() -> new RecruitmentCaseEntity(playerUniqueId));
        String safeInterest = enumValue(interestStatus, INTEREST,
                defaultText(entity.getInterestStatus(), "unknown"), "interestStatus");
        String safeStage = enumValue(dealStage, STAGES,
                defaultText(entity.getDealStage(), "monitoring"), "dealStage");
        String safeSource = enumValue(source, SOURCES,
                defaultText(entity.getSource(), "user"), "source");
        String gameDate = blank(observedGameDate)
                ? defaultText(entity.getObservedGameDate(),
                        metadata.findById("game_date").map(row -> row.getValue()).orElse(null))
                : observedGameDate.trim();

        entity.update(safeInterest, safeStage,
                quotedFee == null ? entity.getQuotedFee() : nonNegative(quotedFee),
                quotedWeeklyWage == null ? entity.getQuotedWeeklyWage() : nonNegative(quotedWeeklyWage),
                note == null ? entity.getNote() : trimTo(note, 2048),
                safeSource,
                gameDate);
        cases.save(entity);
        return toMap(entity, player, currentGameDate());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> board() {
        String gameDate = currentGameDate();
        return cases.findAll().stream()
                .sorted(Comparator.comparing(RecruitmentCaseEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entity -> toMap(entity, players.findFirstByUniqueId(entity.getPlayerUniqueId()).orElse(null), gameDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public RecruitmentCaseEntity find(Long playerUniqueId) {
        return playerUniqueId == null ? null : cases.findById(playerUniqueId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<Long, RecruitmentCaseEntity> byPlayerUniqueId() {
        return cases.findAll().stream().collect(Collectors.toMap(
                RecruitmentCaseEntity::getPlayerUniqueId,
                Function.identity()));
    }

    public static boolean excludesCandidate(RecruitmentCaseEntity entity) {
        if (entity == null) {
            return false;
        }
        return "not_interested".equals(entity.getInterestStatus())
                || Set.of("contract_rejected", "joining_other_club", "joined", "dismissed")
                .contains(entity.getDealStage());
    }

    public static boolean verifiedInterest(RecruitmentCaseEntity entity) {
        return entity != null && "interested".equals(entity.getInterestStatus())
                && !"heuristic".equals(entity.getSource());
    }

    private String currentGameDate() {
        return metadata.findById("game_date").map(row -> row.getValue()).orElse(null);
    }

    private static Map<String, Object> toMap(
            RecruitmentCaseEntity entity,
            PlayerEntity player,
            String currentGameDate) {
        Map<String, Object> out = new LinkedHashMap<>();
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
        out.put("updated_at", entity.getUpdatedAt());
        out.put("stale", currentGameDate != null && entity.getObservedGameDate() != null
                && !currentGameDate.equals(entity.getObservedGameDate()));
        return out;
    }

    private static String enumValue(String raw, Set<String> supported, String fallback, String field) {
        if (blank(raw)) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!supported.contains(normalized)) {
            throw new IllegalArgumentException(field + " must be one of " + supported);
        }
        return normalized;
    }

    private static Long nonNegative(Long value) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException("quoted money values cannot be negative");
        }
        return value;
    }

    private static String trimTo(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maximum ? trimmed : trimmed.substring(0, maximum);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultText(String value, String fallback) {
        return blank(value) ? fallback : value;
    }
}
