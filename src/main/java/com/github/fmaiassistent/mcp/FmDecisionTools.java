package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.decision.RoleFitService;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.RecruitmentCaseEntity;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import com.github.fmaiassistent.recruitment.RecruitmentCaseService;
import com.github.fmaiassistent.service.ClubDatabaseService;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.github.fmaiassistent.tactic.TacticDefinition;
import com.github.fmaiassistent.web.ui.PositionTextFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Service
public class FmDecisionTools {
    private static final List<String> POSITIONS = List.of(
            "GK", "DL", "DC", "DR", "WBL", "DMC", "WBR",
            "ML", "MC", "MR", "AML", "AMC", "AMR", "ST");
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 30;

    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final ManagedClubContextService managedClubs;
    private final TacticContextService tactics;
    private final RoleFitService roleFits;
    private final RecruitmentCaseService recruitmentCases;
    private final SnapshotStatusService snapshots;

    public FmDecisionTools(
            PlayerDatabaseService players,
            ClubDatabaseService clubs,
            ManagedClubContextService managedClubs,
            TacticContextService tactics,
            RoleFitService roleFits,
            RecruitmentCaseService recruitmentCases,
            SnapshotStatusService snapshots) {
        this.players = players;
        this.clubs = clubs;
        this.managedClubs = managedClubs;
        this.tactics = tactics;
        this.roleFits = roleFits;
        this.recruitmentCases = recruitmentCases;
        this.snapshots = snapshots;
    }

    @Tool(name = "fm26_analyze_squad", description = "Analyze squad depth, both phases of the loaded FM26 tactic, contracts, age, injuries, loans and future transfers. Use this to decide which positions need recruitment before searching for players.")
    @Transactional(readOnly = true)
    public Map<String, Object> analyzeSquad(
            @ToolParam(required = false, description = "Club name. Defaults to the club managed by the human manager.") String managingClub,
            @ToolParam(required = false, description = "Minimum positional ability for a viable option, 1-20. Defaults to 15.") Integer minimumPositionScore,
            @ToolParam(required = false, description = "Contract-risk horizon in months. Defaults to 12.") Integer contractHorizonMonths) {
        String clubName = resolveManagingClub(managingClub);
        int minimum = clamp(minimumPositionScore == null ? 15 : minimumPositionScore, 1, 20);
        int horizon = clamp(contractHorizonMonths == null ? 12 : contractHorizonMonths, 1, 60);
        List<PlayerEntity> all = players.findAllPlayerEntities();
        List<PlayerEntity> squad = currentSquad(all, clubName);
        TacticContext tactic = tactics.current();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshots.reference());
        out.put("club", clubName);
        out.put("squad_size", squad.size());
        out.put("tactic", tactic.active() ? tactic.title() : null);
        out.put("tactic_loaded", tactic.active() && tactic.definition() != null);
        out.put("position_depth", positionDepth(squad, minimum));

        List<Map<String, Object>> slotRows = tactic.definition() == null
                ? List.of()
                : tacticSlots(squad, tactic.definition(), minimum);
        out.put("tactic_slots", slotRows);
        out.put("recruitment_priorities", recruitmentPriorities(slotRows));
        out.put("contract_risks", contractRisks(squad, horizon));
        out.put("age_risks", squad.stream().filter(player -> parsedAge(player) != null && age(player) >= 30)
                .sorted(Comparator.comparingInt(FmDecisionTools::age).reversed())
                .map(this::compactPlayer).toList());
        out.put("injuries", squad.stream().filter(player -> Boolean.TRUE.equals(player.getInjured()))
                .map(this::compactPlayer).toList());
        out.put("loaned_out", all.stream()
                .filter(player -> equalsText(player.getClub(), clubName)
                        && !blank(player.getPlayingClub())
                        && !equalsText(player.getPlayingClub(), clubName))
                .map(this::compactPlayer).toList());
        out.put("loaned_in", squad.stream()
                .filter(player -> equalsText(player.getPlayingClub(), clubName) && !equalsText(player.getClub(), clubName))
                .map(this::compactPlayer).toList());
        out.put("future_arrivals", all.stream()
                .filter(player -> Boolean.TRUE.equals(player.getTransferAgreed()))
                .filter(player -> equalsText(player.getFutureTransferClub(), clubName))
                .map(this::compactPlayer).toList());
        out.put("youth_pathways", squad.stream()
                .filter(player -> parsedAge(player) != null && age(player) <= 23
                        && value(player.getPa()) - value(player.getCa()) >= 15)
                .sorted(Comparator.comparingInt((PlayerEntity player) -> value(player.getPa())).reversed())
                .map(this::compactPlayer).toList());
        out.put("limitations", List.of(
                "No condition, morale, match sharpness, suspension or match-performance data is loaded.",
                "Best-XI suggestions are attribute and availability estimates, not match-day selections."));
        return out;
    }

    @Tool(name = "fm26_compare_players", description = "Compare 2-8 players by FM Unique ID with both-phase tactic fit, squad improvement, affordability, development outlook, risks and verified recruitment evidence.")
    @Transactional(readOnly = true)
    public Map<String, Object> comparePlayers(
            @ToolParam(description = "Two to eight FM26 UNIQUE_ID/player_unique_id values.") List<Long> playerUniqueIds,
            @ToolParam(required = false, description = "Managing club for squad and budget comparison. Defaults to the detected managed club.") String managingClub,
            @ToolParam(required = false, description = "One-based loaded-tactic slot. Omit to use each player's best slot.") Integer tacticSlot) {
        List<Long> ids = requireIds(playerUniqueIds, 2, 8);
        String clubName = resolveManagingClub(managingClub);
        ClubEntity club = requireClub(clubName);
        List<PlayerEntity> all = players.findAllPlayerEntities();
        Map<Long, PlayerEntity> byId = byUniqueId(all);
        TacticDefinition tactic = tactics.current().definition();
        TacticDefinition.TacticSlot requestedSlot = resolveSlot(tactic, tacticSlot);
        int squadBenchmark = firstTeamAverageCa(currentSquad(all, clubName));

        List<Map<String, Object>> comparisons = ids.stream()
                .map(id -> requirePlayer(byId, id))
                .map(player -> comparisonMap(player, club, tactic, requestedSlot, squadBenchmark))
                .sorted(Comparator.comparingDouble(row -> -((Number) row.get("decision_score")).doubleValue()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshots.reference());
        out.put("club", clubName);
        out.put("tactic", tactic == null ? null : tactic.name());
        out.put("requested_slot", tacticSlot);
        out.put("players", comparisons);
        out.put("recommendation", comparisons.isEmpty() ? null : comparisons.getFirst().get("name"));
        out.put("guidance", "Scores are deterministic comparisons. Expected transfer fees, wage demands and willingness remain unknown unless verified evidence is present.");
        return out;
    }

    @Tool(name = "fm26_find_replacements", description = "Find players with a similar attribute and positional profile to a reference player, then rank them for the managing club and loaded tactic.")
    @Transactional(readOnly = true)
    public Map<String, Object> findReplacements(
            @ToolParam(description = "FM26 UNIQUE_ID of the player to replace or emulate.") Long referencePlayerUniqueId,
            @ToolParam(required = false, description = "Managing club. Defaults to the detected managed club.") String managingClub,
            @ToolParam(required = false, description = "Maximum candidate age.") Integer maxAge,
            @ToolParam(required = false, description = "Literal maximum asking price in pounds; it is not silently capped to the club budget.") Long maxAskingPrice,
            @ToolParam(required = false, description = "Minimum current ability.") Integer minCurrentAbility,
            @ToolParam(required = false, description = "Minimum ability at the reference player's best position. Defaults to 15.") Integer minimumPositionScore,
            @ToolParam(required = false, description = "Maximum candidates. Defaults to 8, maximum 30.") Integer limit) {
        String clubName = resolveManagingClub(managingClub);
        ClubEntity club = requireClub(clubName);
        List<PlayerEntity> all = players.findAllPlayerEntities();
        PlayerEntity reference = requirePlayer(byUniqueId(all), referencePlayerUniqueId);
        String position = bestPosition(reference);
        int minimum = clamp(minimumPositionScore == null ? 15 : minimumPositionScore, 1, 20);
        int safeLimit = clamp(limit == null ? DEFAULT_LIMIT : limit, 1, MAX_LIMIT);
        long budget = Math.max(0, value(club.getTransferBudget()));
        Map<Long, RecruitmentCaseEntity> evidenceByPlayer = recruitmentCases.byPlayerUniqueId();

        List<Map<String, Object>> candidates = all.stream()
                .filter(player -> !Objects.equals(player.getUniqueId(), referencePlayerUniqueId))
                .filter(player -> sameGender(player, club))
                .filter(player -> !belongsToClub(player, clubName))
                .filter(player -> maxAge == null || age(player) <= maxAge)
                .filter(player -> minCurrentAbility == null || value(player.getCa()) >= minCurrentAbility)
                .filter(player -> roleFits.positionScore(player, position) >= minimum)
                .filter(player -> maxAskingPrice == null || value(player.getAskingPrice()) == 0
                        || value(player.getAskingPrice()) <= Math.max(0, maxAskingPrice))
                .filter(player -> !Boolean.TRUE.equals(player.getTransferAgreed()))
                .filter(player -> !RecruitmentCaseService.excludesCandidate(evidenceByPlayer.get(player.getUniqueId())))
                .map(player -> replacementMap(reference, player, club, position, budget,
                        evidenceByPlayer.get(player.getUniqueId())))
                .sorted(Comparator.comparingDouble(row -> -((Number) row.get("replacement_score")).doubleValue()))
                .limit(safeLimit)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshots.reference());
        out.put("club", clubName);
        out.put("reference", compactPlayer(reference));
        out.put("reference_position", position);
        out.put("criteria", Map.of(
                "minimum_position_score", minimum,
                "max_age", maxAge == null ? "none" : maxAge,
                "max_asking_price", maxAskingPrice == null ? "none" : maxAskingPrice,
                "min_current_ability", minCurrentAbility == null ? "none" : minCurrentAbility));
        out.put("returned", candidates.size());
        out.put("candidates", candidates);
        return out;
    }

    @Tool(name = "fm26_plan_squad_moves", description = "Model incoming and outgoing players without changing FM26. Returns depth, tactic coverage, known financial effects and unknown inputs before any transfer decision.")
    @Transactional(readOnly = true)
    public Map<String, Object> planSquadMoves(
            @ToolParam(required = false, description = "Incoming FM26 player UNIQUE_ID values.") List<Long> incomingPlayerUniqueIds,
            @ToolParam(required = false, description = "Outgoing squad player UNIQUE_ID values.") List<Long> outgoingPlayerUniqueIds,
            @ToolParam(required = false, description = "Optional verified fee and weekly-wage quotes keyed by player UNIQUE_ID.") List<PlayerQuote> quotes,
            @ToolParam(required = false, description = "Managing club. Defaults to the detected managed club.") String managingClub) {
        List<Long> incomingIds = optionalIds(incomingPlayerUniqueIds);
        List<Long> outgoingIds = optionalIds(outgoingPlayerUniqueIds);
        if (incomingIds.isEmpty() && outgoingIds.isEmpty()) {
            throw new IllegalArgumentException("supply at least one incoming or outgoing player UNIQUE_ID");
        }
        Set<Long> overlap = new HashSet<>(incomingIds);
        overlap.retainAll(outgoingIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("players cannot be both incoming and outgoing: " + overlap);
        }

        String clubName = resolveManagingClub(managingClub);
        ClubEntity club = requireClub(clubName);
        List<PlayerEntity> all = players.findAllPlayerEntities();
        Map<Long, PlayerEntity> byId = byUniqueId(all);
        List<PlayerEntity> before = new ArrayList<>(currentSquad(all, clubName));
        List<PlayerEntity> incoming = incomingIds.stream().map(id -> requirePlayer(byId, id)).toList();
        List<PlayerEntity> outgoing = outgoingIds.stream().map(id -> requirePlayer(byId, id)).toList();
        Set<Long> currentSquadIds = before.stream().map(PlayerEntity::getUniqueId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<Long> invalidOutgoing = outgoingIds.stream().filter(id -> !currentSquadIds.contains(id)).toList();
        if (!invalidOutgoing.isEmpty()) {
            throw new IllegalArgumentException("outgoing players are not in the current squad: " + invalidOutgoing);
        }
        List<Long> invalidIncoming = incomingIds.stream().filter(currentSquadIds::contains).toList();
        if (!invalidIncoming.isEmpty()) {
            throw new IllegalArgumentException("incoming players are already in the current squad: " + invalidIncoming);
        }
        List<PlayerEntity> after = before.stream()
                .filter(player -> !outgoingIds.contains(player.getUniqueId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (PlayerEntity player : incoming) {
            if (after.stream().noneMatch(current -> Objects.equals(current.getUniqueId(), player.getUniqueId()))) {
                after.add(player);
            }
        }

        Map<Long, PlayerQuote> quoteMap = Optional.ofNullable(quotes).orElse(List.of()).stream()
                .collect(java.util.stream.Collectors.toMap(PlayerQuote::playerUniqueId, Function.identity(), (left, right) -> right));
        FinancialProjection finances = finances(club, incoming, outgoing, quoteMap);
        TacticDefinition tactic = tactics.current().definition();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshots.reference());
        out.put("club", clubName);
        out.put("incoming", incoming.stream().map(this::compactPlayer).toList());
        out.put("outgoing", outgoing.stream().map(this::compactPlayer).toList());
        out.put("finances", finances.toMap());
        out.put("position_depth_before", positionDepth(before, 15));
        out.put("position_depth_after", positionDepth(after, 15));
        if (tactic != null) {
            out.put("tactic_coverage_before", coverageSummary(tacticSlots(before, tactic, 15)));
            out.put("tactic_coverage_after", coverageSummary(tacticSlots(after, tactic, 15)));
        }
        out.put("warnings", scenarioWarnings(incoming, outgoing, finances));
        return out;
    }

    @Tool(name = "fm26_update_recruitment_case", description = "Store user or in-game verified recruitment evidence for a player. This writes only to the local app and overrides heuristic willingness in future decision tools.")
    public Map<String, Object> updateRecruitmentCase(
            @ToolParam(description = "FM26 player UNIQUE_ID.") Long playerUniqueId,
            @ToolParam(required = false, description = "unknown, interested or not_interested.") String interestStatus,
            @ToolParam(required = false, description = "monitoring, bid_submitted, bid_accepted, bid_rejected, contract_negotiation, contract_agreed, contract_rejected, joining_other_club, joined or dismissed.") String dealStage,
            @ToolParam(required = false, description = "Verified or quoted transfer fee in pounds.") Long quotedFee,
            @ToolParam(required = false, description = "Verified or quoted weekly wage in pounds.") Long quotedWeeklyWage,
            @ToolParam(required = false, description = "Short supporting note.") String note,
            @ToolParam(required = false, description = "user, agent_enquiry, game or heuristic. Defaults to user.") String source,
            @ToolParam(required = false, description = "FM game date when observed, YYYY-MM-DD. Defaults to loaded game date.") String observedGameDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshots.reference());
        out.put("case", recruitmentCases.update(playerUniqueId, interestStatus, dealStage,
                quotedFee, quotedWeeklyWage, note, source, observedGameDate));
        return out;
    }

    @Tool(name = "fm26_get_recruitment_board", description = "Get persistent recruitment cases, verified interest, deal stages, quotes and stale evidence.")
    public Map<String, Object> getRecruitmentBoard() {
        List<Map<String, Object>> cases = recruitmentCases.board();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshots.reference());
        out.put("count", cases.size());
        out.put("cases", cases);
        return out;
    }

    private List<Map<String, Object>> tacticSlots(
            List<PlayerEntity> squad,
            TacticDefinition tactic,
            int minimumPositionScore) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TacticDefinition.TacticSlot slot : tactic.slots()) {
            List<SlotCandidate> candidates = squad.stream()
                    .map(player -> new SlotCandidate(player, roleFits.slotFit(player, slot)))
                    .sorted(Comparator.comparingDouble((SlotCandidate candidate) -> candidate.fit().overall()).reversed())
                    .toList();
            List<Map<String, Object>> options = candidates.stream().limit(3)
                    .map(candidate -> slotCandidateMap(candidate.player(), candidate.fit()))
                    .toList();
            long viable = candidates.stream().map(SlotCandidate::fit)
                    .filter(fit -> fit.viable() && fit.positionFit() >= minimumPositionScore).count();
            long available = candidates.stream()
                    .filter(candidate -> !Boolean.TRUE.equals(candidate.player().getInjured()))
                    .map(SlotCandidate::fit)
                    .filter(fit -> fit.viable() && fit.positionFit() >= minimumPositionScore).count();
            String coverage = viable >= 2 ? "healthy" : viable == 1 ? "thin" : "gap";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot.index());
            row.put("in_possession", phaseRoleMap(slot.inPossession()));
            row.put("out_of_possession", phaseRoleMap(slot.outOfPossession()));
            row.put("coverage", coverage);
            row.put("viable_options", viable);
            row.put("availability", available >= 2 ? "healthy" : available == 1 ? "thin" : "gap");
            row.put("available_viable_options", available);
            row.put("best_options", options);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> recruitmentPriorities(List<Map<String, Object>> slots) {
        return slots.stream()
                .filter(slot -> !"healthy".equals(slot.get("coverage")))
                .sorted(Comparator.comparingInt(slot -> "gap".equals(slot.get("coverage")) ? 0 : 1))
                .map(slot -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("slot", slot.get("slot"));
                    out.put("coverage", slot.get("coverage"));
                    out.put("in_possession", slot.get("in_possession"));
                    out.put("out_of_possession", slot.get("out_of_possession"));
                    out.put("reason", "gap".equals(slot.get("coverage"))
                            ? "no viable option across both phases" : "only one viable option");
                    return out;
                }).toList();
    }

    private List<Map<String, Object>> positionDepth(List<PlayerEntity> squad, int minimum) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String position : POSITIONS) {
            List<PlayerEntity> options = squad.stream()
                    .filter(player -> roleFits.positionScore(player, position) >= minimum)
                    .sorted(Comparator.comparingInt((PlayerEntity player) -> roleFits.positionScore(player, position)).reversed()
                            .thenComparing(Comparator.comparingInt((PlayerEntity player) -> value(player.getCa())).reversed()))
                    .toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", position);
            row.put("coverage", options.size() >= 2 ? "healthy" : options.size() == 1 ? "thin" : "gap");
            row.put("viable_options", options.size());
            long available = options.stream().filter(player -> !Boolean.TRUE.equals(player.getInjured())).count();
            row.put("availability", available >= 2 ? "healthy" : available == 1 ? "thin" : "gap");
            row.put("available_options", available);
            row.put("best_options", options.stream().limit(3).map(player -> {
                Map<String, Object> option = new LinkedHashMap<>(compactPlayer(player));
                option.put("position_score", roleFits.positionScore(player, position));
                return option;
            }).toList());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> contractRisks(List<PlayerEntity> squad, int months) {
        LocalDate gameDate = squad.stream().map(PlayerEntity::getAgeAsOf).map(FmDecisionTools::date)
                .filter(Objects::nonNull).findFirst().orElse(LocalDate.now());
        LocalDate cutoff = gameDate.plusMonths(months);
        return squad.stream()
                .filter(player -> {
                    LocalDate end = date(player.getContractEndDate());
                    return end != null && !end.isAfter(cutoff);
                })
                .sorted(Comparator.comparing(player -> date(player.getContractEndDate())))
                .map(player -> {
                    Map<String, Object> row = new LinkedHashMap<>(compactPlayer(player));
                    row.put("contract_end", player.getContractEndDate());
                    row.put("months_remaining", java.time.temporal.ChronoUnit.MONTHS.between(
                            gameDate, date(player.getContractEndDate())));
                    return row;
                }).toList();
    }

    private Map<String, Object> comparisonMap(
            PlayerEntity player,
            ClubEntity club,
            TacticDefinition tactic,
            TacticDefinition.TacticSlot requestedSlot,
            int squadBenchmark) {
        RoleFitService.SlotFit fit = null;
        if (requestedSlot != null) {
            fit = roleFits.slotFit(player, requestedSlot);
        } else if (tactic != null) {
            fit = tactic.slots().stream().map(slot -> roleFits.slotFit(player, slot))
                    .max(Comparator.comparingDouble(RoleFitService.SlotFit::overall)).orElse(null);
        }
        RecruitmentCaseEntity evidence = recruitmentCases.find(player.getUniqueId());
        DevelopmentOutlook development = developmentOutlook(player);
        long budget = Math.max(0, value(club.getTransferBudget()));
        double fitScore = fit == null ? bestPositionScore(player) / 20.0 * 100 : fit.overall();
        double decision = round1(fitScore * 0.55
                + Math.min(100, value(player.getCa()) / 2.0) * 0.25
                + development.score() * 0.10
                + affordabilityScore(player, budget) * 0.10);

        Map<String, Object> out = new LinkedHashMap<>(compactPlayer(player));
        out.put("decision_score", decision);
        out.put("score_components", Map.of(
                "tactic_or_position_fit", round1(fitScore),
                "current_ability", round1(Math.min(100, value(player.getCa()) / 2.0)),
                "development_outlook", development.score(),
                "affordability", affordabilityScore(player, budget)));
        out.put("ca_vs_squad_first_team", value(player.getCa()) - squadBenchmark);
        out.put("tactic_fit", fit == null ? null : fit.toMap());
        out.put("affordability", affordability(player, budget));
        out.put("development", development.toMap());
        out.put("risks", risks(player, evidence));
        out.put("recruitment_evidence", evidenceMap(evidence));
        return out;
    }

    private Map<String, Object> replacementMap(
            PlayerEntity reference,
            PlayerEntity candidate,
            ClubEntity club,
            String position,
            long budget,
            RecruitmentCaseEntity evidence) {
        double similarity = attributeSimilarity(reference, candidate);
        double ca = clamp01((value(candidate.getCa()) - value(reference.getCa()) + 40.0) / 80.0) * 100;
        double potential = Math.min(100, value(candidate.getPa()) / 2.0);
        double affordability = affordabilityScore(candidate, budget);
        Integer candidateAge = parsedAge(candidate);
        Integer referenceAge = parsedAge(reference);
        double age = candidateAge == null || referenceAge == null ? 50
                : candidateAge <= referenceAge ? 100
                : Math.max(0, 100 - (candidateAge - referenceAge) * 10.0);
        double score = round1(similarity * 0.50 + ca * 0.20 + potential * 0.15 + affordability * 0.10 + age * 0.05);
        Map<String, Object> out = new LinkedHashMap<>(compactPlayer(candidate));
        out.put("replacement_score", score);
        out.put("attribute_similarity", round1(similarity));
        out.put("position", position);
        out.put("position_score", roleFits.positionScore(candidate, position));
        out.put("ca_vs_reference", value(candidate.getCa()) - value(reference.getCa()));
        out.put("pa_vs_reference", value(candidate.getPa()) - value(reference.getPa()));
        out.put("affordability", affordability(candidate, budget));
        out.put("development", developmentOutlook(candidate).toMap());
        out.put("risks", risks(candidate, evidence));
        out.put("recruitment_evidence", evidenceMap(evidence));
        return out;
    }

    private DevelopmentOutlook developmentOutlook(PlayerEntity player) {
        Integer currentAge = parsedAge(player);
        int ageScore = currentAge == null ? 50
                : currentAge <= 20 ? 100 : currentAge <= 23 ? 85
                : currentAge <= 26 ? 55 : currentAge <= 29 ? 25 : 5;
        int personality = average(
                number(player, "PROFESSIONALISM"),
                number(player, "DETERMINATION"),
                number(player, "AMBITION")) * 5;
        int potentialGap = Math.min(100, Math.max(0, value(player.getPa()) - value(player.getCa())) * 2);
        ClubEntity source = player.getClubEntity();
        int facilities = source == null ? 50 : averageNullable(
                source.getTrainingFacilities(), source.getYouthFacilities(), source.getYouthCoaching()) * 5;
        double score = round1(ageScore * 0.35 + personality * 0.30 + potentialGap * 0.25 + facilities * 0.10);
        String band = score >= 75 ? "strong" : score >= 50 ? "moderate" : "limited";
        return new DevelopmentOutlook(score, band, value(player.getPa()) - value(player.getCa()), ageScore,
                personality, facilities);
    }

    private static List<String> risks(PlayerEntity player, RecruitmentCaseEntity evidence) {
        List<String> risks = new ArrayList<>();
        if (Boolean.TRUE.equals(player.getInjured())) risks.add("currently_injured");
        if (number(player, "INJURY_PRONENESS") >= 15) risks.add("high_injury_proneness");
        if (number(player, "CONSISTENCY") > 0 && number(player, "CONSISTENCY") <= 10) risks.add("low_consistency");
        if (number(player, "IMPORTANT_MATCHES") > 0 && number(player, "IMPORTANT_MATCHES") <= 10) risks.add("low_important_matches");
        if (Boolean.TRUE.equals(player.getTransferAgreed())) risks.add("future_transfer_agreed");
        if (RecruitmentCaseService.excludesCandidate(evidence)) risks.add("verified_recruitment_blocker");
        if (value(player.getAskingPrice()) == 0 && !blank(player.getClub())) risks.add("asking_price_unknown");
        return risks;
    }

    private FinancialProjection finances(
            ClubEntity club,
            List<PlayerEntity> incoming,
            List<PlayerEntity> outgoing,
            Map<Long, PlayerQuote> quotes) {
        long knownSpend = 0;
        long knownReceipts = 0;
        long wageIncrease = 0;
        long wageDecrease = 0;
        List<Long> unknownIncomingFees = new ArrayList<>();
        List<Long> unknownOutgoingFees = new ArrayList<>();
        List<Long> unknownIncomingWages = new ArrayList<>();
        for (PlayerEntity player : incoming) {
            PlayerQuote quote = quotes.get(player.getUniqueId());
            Long fee = quote == null ? null : quote.fee();
            if (fee == null && value(player.getAskingPrice()) > 0) fee = player.getAskingPrice();
            if (fee == null) unknownIncomingFees.add(player.getUniqueId()); else knownSpend += Math.max(0, fee);
            Long wage = quote == null ? null : quote.weeklyWage();
            if (wage == null) unknownIncomingWages.add(player.getUniqueId()); else wageIncrease += Math.max(0, wage);
        }
        for (PlayerEntity player : outgoing) {
            PlayerQuote quote = quotes.get(player.getUniqueId());
            Long fee = quote == null ? null : quote.fee();
            if (fee == null) unknownOutgoingFees.add(player.getUniqueId()); else knownReceipts += Math.max(0, fee);
            wageDecrease += Math.max(0, value(player.getSalaryWeeklyRaw()));
        }
        long budget = Math.max(0, value(club.getTransferBudget()));
        return new FinancialProjection(
                budget, knownSpend, knownReceipts, budget - knownSpend + knownReceipts,
                wageIncrease, wageDecrease, wageIncrease - wageDecrease,
                unknownIncomingFees, unknownOutgoingFees, unknownIncomingWages);
    }

    private static List<String> scenarioWarnings(
            List<PlayerEntity> incoming,
            List<PlayerEntity> outgoing,
            FinancialProjection finances) {
        List<String> warnings = new ArrayList<>();
        if (!finances.unknownIncomingFees().isEmpty()) warnings.add("incoming_fees_unknown");
        if (!finances.unknownOutgoingFees().isEmpty()) warnings.add("outgoing_sale_proceeds_unknown");
        if (!finances.unknownIncomingWages().isEmpty()) warnings.add("incoming_wage_demands_unknown");
        if (finances.knownRemainingBudget() < 0) warnings.add("known_spend_exceeds_transfer_budget");
        if (incoming.stream().anyMatch(player -> Boolean.TRUE.equals(player.getTransferAgreed()))) warnings.add("incoming_player_has_future_transfer");
        if (outgoing.stream().anyMatch(player -> Boolean.TRUE.equals(player.getInjured()))) warnings.add("outgoing_player_currently_injured");
        return warnings;
    }

    private Map<String, Object> compactPlayer(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player_unique_id", player.getUniqueId());
        out.put("name", player.getName());
        out.put("age", parsedAge(player));
        out.put("nationality", player.getNationality());
        out.put("club", player.getClub());
        out.put("playing_club", player.getPlayingClub());
        out.put("position_text", PositionTextFormatter.format(player));
        out.put("ca", player.getCa());
        out.put("pa", player.getPa());
        out.put("asking_price", value(player.getAskingPrice()) == 0 ? null : player.getAskingPrice());
        out.put("salary_weekly", player.getSalaryWeeklyRaw());
        out.put("contract_end", player.getContractEndDate());
        out.put("injured", player.getInjured());
        out.put("transfer_agreed", player.getTransferAgreed());
        return out;
    }

    private static Map<String, Object> slotCandidateMap(PlayerEntity player, RoleFitService.SlotFit fit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player_unique_id", player.getUniqueId());
        out.put("name", player.getName());
        out.put("age", parsedAge(player));
        out.put("ca", player.getCa());
        out.put("pa", player.getPa());
        out.put("contract_end", player.getContractEndDate());
        out.put("injured", player.getInjured());
        out.put("fit", fit.toMap());
        return out;
    }

    private static Map<String, Object> phaseRoleMap(TacticDefinition.PhaseRole role) {
        if (role == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("position", role.position());
        out.put("role", role.role());
        out.put("duty", role.duty());
        return out;
    }

    private static Map<String, Object> evidenceMap(RecruitmentCaseEntity evidence) {
        if (evidence == null) return Map.of("available", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("interest_status", evidence.getInterestStatus());
        out.put("deal_stage", evidence.getDealStage());
        out.put("quoted_fee", evidence.getQuotedFee());
        out.put("quoted_weekly_wage", evidence.getQuotedWeeklyWage());
        out.put("source", evidence.getSource());
        out.put("observed_game_date", evidence.getObservedGameDate());
        out.put("updated_at", evidence.getUpdatedAt());
        return out;
    }

    private static Map<String, Object> affordability(PlayerEntity player, long budget) {
        long price = value(player.getAskingPrice());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asking_price", price == 0 ? null : price);
        out.put("transfer_budget", budget);
        out.put("status", price == 0 ? "unknown" : price <= budget ? "within_budget" : "requires_sales");
        return out;
    }

    private static double affordabilityScore(PlayerEntity player, long budget) {
        long price = value(player.getAskingPrice());
        if (blank(player.getClub())) return 100;
        if (price == 0) return 35;
        if (budget <= 0) return 0;
        return round1(Math.max(0, Math.min(100, (1.0 - price / (double) budget) * 100)));
    }

    private static double attributeSimilarity(PlayerEntity left, PlayerEntity right) {
        double difference = 0;
        int fields = 0;
        for (FieldDef field : AttributeDefinitions.VISIBLE_FIELDS) {
            int leftValue = number(left, columnName(field));
            int rightValue = number(right, columnName(field));
            difference += Math.abs(leftValue - rightValue);
            fields++;
        }
        return fields == 0 ? 0 : Math.max(0, 100 * (1 - difference / (fields * 19.0)));
    }

    private static String bestPosition(PlayerEntity player) {
        return POSITIONS.stream().max(Comparator.comparingInt(position -> {
            String column = RoleFitService.positionColumn(position);
            return column == null ? 0 : number(player, column);
        })).orElse("ST");
    }

    private static int bestPositionScore(PlayerEntity player) {
        return POSITIONS.stream().map(RoleFitService::positionColumn).filter(Objects::nonNull)
                .mapToInt(column -> number(player, column)).max().orElse(0);
    }

    private String resolveManagingClub(String requested) {
        if (!blank(requested)) return requested.trim();
        ManagedClubContext context = managedClubs.current();
        if (!context.available() || blank(context.clubName())) {
            throw new IllegalArgumentException("managingClub is required because the current managed club is unavailable");
        }
        return context.clubName();
    }

    private ClubEntity requireClub(String name) {
        return clubs.findAllClubs().stream()
                .filter(club -> equalsText(club.getName(), name))
                .max(Comparator.comparingInt(club -> value(club.getReputation())))
                .orElseThrow(() -> new IllegalArgumentException("club not found: " + name));
    }

    private static List<PlayerEntity> currentSquad(List<PlayerEntity> players, String club) {
        return players.stream().filter(player -> equalsText(player.getPlayingClub(), club)
                || (blank(player.getPlayingClub()) && equalsText(player.getClub(), club))).toList();
    }

    private static boolean belongsToClub(PlayerEntity player, String club) {
        return equalsText(player.getClub(), club) || equalsText(player.getPlayingClub(), club);
    }

    private static boolean sameGender(PlayerEntity player, ClubEntity club) {
        return blank(player.getGender()) || blank(club.getGender()) || equalsText(player.getGender(), club.getGender());
    }

    private static int firstTeamAverageCa(List<PlayerEntity> squad) {
        return (int) Math.round(squad.stream().map(PlayerEntity::getCa).filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder()).limit(11).mapToInt(Integer::intValue).average().orElse(0));
    }

    private static TacticDefinition.TacticSlot resolveSlot(TacticDefinition tactic, Integer index) {
        if (index == null) return null;
        if (tactic == null) throw new IllegalArgumentException("no structured tactic is loaded");
        return tactic.slots().stream().filter(slot -> slot.index() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tacticSlot must be between 1 and " + tactic.slots().size()));
    }

    private static Map<Long, PlayerEntity> byUniqueId(List<PlayerEntity> players) {
        Map<Long, PlayerEntity> out = new HashMap<>();
        players.stream().filter(player -> player.getUniqueId() != null)
                .forEach(player -> out.merge(player.getUniqueId(), player,
                        (left, right) -> value(right.getWorldReputation()) > value(left.getWorldReputation()) ? right : left));
        return out;
    }

    private static PlayerEntity requirePlayer(Map<Long, PlayerEntity> players, Long id) {
        if (id == null) throw new IllegalArgumentException("player UNIQUE_ID cannot be null");
        PlayerEntity player = players.get(id);
        if (player == null) throw new IllegalArgumentException("player not found for UNIQUE_ID: " + id);
        return player;
    }

    private static List<Long> requireIds(List<Long> ids, int minimum, int maximum) {
        List<Long> safe = optionalIds(ids);
        if (safe.size() < minimum || safe.size() > maximum) {
            throw new IllegalArgumentException("supply " + minimum + "-" + maximum + " unique player UNIQUE_ID values");
        }
        return safe;
    }

    private static List<Long> optionalIds(List<Long> ids) {
        if (ids == null) return List.of();
        List<Long> safe = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (safe.size() != ids.size()) throw new IllegalArgumentException("player UNIQUE_ID values cannot be null or duplicated");
        return safe;
    }

    private static Map<String, Object> coverageSummary(List<Map<String, Object>> slots) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("healthy", slots.stream().filter(slot -> "healthy".equals(slot.get("coverage"))).count());
        out.put("thin", slots.stream().filter(slot -> "thin".equals(slot.get("coverage"))).count());
        out.put("gaps", slots.stream().filter(slot -> "gap".equals(slot.get("coverage"))).count());
        out.put("slots", slots);
        return out;
    }

    private static LocalDate date(String raw) {
        if (blank(raw)) return null;
        try { return LocalDate.parse(raw); } catch (DateTimeParseException exception) { return null; }
    }

    private static int number(PlayerEntity player, String column) {
        Object raw = player.getColumnValue(column);
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static String columnName(FieldDef field) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < field.name().length(); index++) {
            char ch = field.name().charAt(index);
            if (Character.isUpperCase(ch) && index > 0) out.append('_');
            out.append(Character.toUpperCase(ch));
        }
        return out.toString();
    }

    private static int age(PlayerEntity player) {
        return Optional.ofNullable(parsedAge(player)).orElse(99);
    }

    private static Integer parsedAge(PlayerEntity player) {
        try { return player.getAge() == null ? null : Integer.parseInt(player.getAge()); }
        catch (NumberFormatException exception) { return null; }
    }

    private static int average(int... values) {
        return values.length == 0 ? 0 : (int) Math.round(java.util.Arrays.stream(values).average().orElse(0));
    }

    private static int averageNullable(Integer... values) {
        return (int) Math.round(java.util.Arrays.stream(values).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).average().orElse(10));
    }

    private static boolean equalsText(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int value(Integer value) { return value == null ? 0 : value; }
    private static long value(Long value) { return value == null ? 0 : value; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }

    private record SlotCandidate(PlayerEntity player, RoleFitService.SlotFit fit) {}

    private record DevelopmentOutlook(
            double score,
            String band,
            int caPaGap,
            int ageComponent,
            int personalityComponent,
            int facilitiesComponent) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("score", score);
            out.put("band", band);
            out.put("ca_pa_gap", caPaGap);
            out.put("components", Map.of(
                    "age", ageComponent,
                    "personality", personalityComponent,
                    "facilities", facilitiesComponent));
            out.put("source", "heuristic");
            out.put("confidence", "medium");
            out.put("limitations", "Does not include minutes played, training performance or coaching assignment.");
            return out;
        }
    }

    private record FinancialProjection(
            long transferBudget,
            long knownSpend,
            long knownReceipts,
            long knownRemainingBudget,
            long knownWeeklyWageIncrease,
            long knownWeeklyWageDecrease,
            long knownWeeklyWageDelta,
            List<Long> unknownIncomingFees,
            List<Long> unknownOutgoingFees,
            List<Long> unknownIncomingWages) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("transfer_budget", transferBudget);
            out.put("known_spend", knownSpend);
            out.put("known_receipts", knownReceipts);
            out.put("known_remaining_budget", knownRemainingBudget);
            out.put("budget_projection_exact", unknownIncomingFees.isEmpty() && unknownOutgoingFees.isEmpty());
            out.put("known_weekly_wage_increase", knownWeeklyWageIncrease);
            out.put("known_weekly_wage_decrease", knownWeeklyWageDecrease);
            out.put("known_weekly_wage_delta", knownWeeklyWageDelta);
            out.put("wage_projection_exact", unknownIncomingWages.isEmpty());
            out.put("unknown_incoming_fees", unknownIncomingFees);
            out.put("unknown_outgoing_fees", unknownOutgoingFees);
            out.put("unknown_incoming_wages", unknownIncomingWages);
            return out;
        }
    }

    public record PlayerQuote(Long playerUniqueId, Long fee, Long weeklyWage) {}
}
