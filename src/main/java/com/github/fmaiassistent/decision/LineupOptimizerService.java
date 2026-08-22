package com.github.fmaiassistent.decision;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.tactic.TacticDefinition;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class LineupOptimizerService {
    private static final long UNREACHABLE = Long.MIN_VALUE / 4;
    private static final long MAX_FM_UID = 0xffff_ffffL;

    private final RoleFitService roleFits;
    private final Cache<FitKey, RoleFitService.SlotFit> fitCache = Caffeine.newBuilder()
            .maximumSize(250_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    public LineupOptimizerService(RoleFitService roleFits) {
        this.roleFits = roleFits;
    }

    public Result optimize(
            List<PlayerEntity> rawPlayers,
            TacticDefinition tactic,
            Constraints constraints) {
        if (tactic == null || tactic.slots().isEmpty()) {
            throw new IllegalArgumentException("no structured tactic is loaded");
        }
        if (tactic.slots().size() > 20) {
            throw new IllegalArgumentException("lineup optimizer supports at most 20 tactic slots");
        }
        Constraints safe = constraints == null ? Constraints.defaults() : constraints.normalized();
        Map<Long, PlayerEntity> playersById = uniquePlayers(rawPlayers);
        List<String> warnings = new ArrayList<>();
        Map<Integer, Assignment> locked = validateLocks(tactic, playersById, safe, warnings);
        Set<Long> lockedPlayerIds = locked.values().stream()
                .map(assignment -> assignment.player().getUniqueId()).collect(java.util.stream.Collectors.toSet());

        List<TacticDefinition.TacticSlot> openSlots = tactic.slots().stream()
                .filter(slot -> !locked.containsKey(slot.index())).toList();
        List<PlayerEntity> eligiblePlayers = playersById.values().stream()
                .filter(player -> !lockedPlayerIds.contains(player.getUniqueId()))
                .filter(player -> !safe.unavailablePlayerUniqueIds().contains(player.getUniqueId()))
                .filter(player -> safe.includeInjured() || !Boolean.TRUE.equals(player.getInjured()))
                .sorted(Comparator.comparing(PlayerEntity::getUniqueId))
                .toList();

        Map<Integer, Assignment> selected = new HashMap<>(locked);
        if (!openSlots.isEmpty() && !eligiblePlayers.isEmpty()) {
            selected.putAll(assignOpenSlots(eligiblePlayers, openSlots, safe));
        }

        List<Assignment> assignments = tactic.slots().stream()
                .map(slot -> selected.get(slot.index()))
                .filter(Objects::nonNull)
                .toList();
        List<TacticDefinition.TacticSlot> unfilled = tactic.slots().stream()
                .filter(slot -> !selected.containsKey(slot.index())).toList();
        if (!unfilled.isEmpty()) warnings.add("unfilled_tactic_slots:" + unfilled.stream()
                .map(TacticDefinition.TacticSlot::index).toList());

        Map<Long, Integer> assignedSlotByPlayer = assignments.stream().collect(java.util.stream.Collectors.toMap(
                assignment -> assignment.player().getUniqueId(),
                assignment -> assignment.slot().index()));
        Map<Integer, List<Alternative>> alternatives = new LinkedHashMap<>();
        for (TacticDefinition.TacticSlot slot : tactic.slots()) {
            Long selectedId = selected.containsKey(slot.index())
                    ? selected.get(slot.index()).player().getUniqueId() : null;
            List<Alternative> options = playersById.values().stream()
                    .filter(player -> !Objects.equals(player.getUniqueId(), selectedId))
                    .filter(player -> !safe.unavailablePlayerUniqueIds().contains(player.getUniqueId()))
                    .filter(player -> safe.includeInjured() || !Boolean.TRUE.equals(player.getInjured()))
                    .map(player -> new Alternative(player, fit(player, slot, safe), assignedSlotByPlayer.get(player.getUniqueId())))
                    .filter(option -> viable(option.fit(), safe.minimumPositionScore()))
                    .sorted(Comparator.comparingDouble((Alternative option) -> option.fit().overall()).reversed()
                            .thenComparing(Comparator.comparingInt(
                                    (Alternative option) -> value(option.player().getCa())).reversed())
                            .thenComparing(option -> option.player().getUniqueId()))
                    .limit(safe.alternativeLimit())
                    .toList();
            alternatives.put(slot.index(), options);
        }

        double total = round1(assignments.stream().mapToDouble(assignment -> assignment.fit().overall()).sum());
        double average = round1(total / tactic.slots().size());
        List<Integer> bottlenecks = tactic.slots().stream()
                .filter(slot -> selected.get(slot.index()) == null
                        || selected.get(slot.index()).fit().overall() < 70.0)
                .sorted(Comparator.comparingDouble(slot -> selected.get(slot.index()) == null
                        ? -1 : selected.get(slot.index()).fit().overall()))
                .map(TacticDefinition.TacticSlot::index)
                .toList();
        return new Result(assignments, unfilled, alternatives, total, average, bottlenecks, List.copyOf(warnings));
    }

    public RoleFitService.SlotFit fit(
            PlayerEntity player,
            TacticDefinition.TacticSlot slot,
            Constraints constraints) {
        Constraints safe = constraints == null ? Constraints.defaults() : constraints.normalized();
        FitKey key = new FitKey(safe.snapshotId(), safe.tacticFingerprint(),
                player.getUniqueId(), slot.index(), slot.hashCode());
        return fitCache.get(key, ignored -> roleFits.slotFit(player, slot));
    }

    public static boolean viable(RoleFitService.SlotFit fit, int minimumPositionScore) {
        List<RoleFitService.PhaseFit> phases = List.of(fit.inPossession(), fit.outOfPossession()).stream()
                .filter(RoleFitService.PhaseFit::present).toList();
        return !phases.isEmpty() && phases.stream().allMatch(phase -> phase.positionScore() >= minimumPositionScore);
    }

    private Map<Integer, Assignment> assignOpenSlots(
            List<PlayerEntity> players,
            List<TacticDefinition.TacticSlot> slots,
            Constraints constraints) {
        RoleFitService.SlotFit[][] fits = new RoleFitService.SlotFit[players.size()][slots.size()];
        long[][] utilities = new long[players.size()][slots.size()];
        for (int playerIndex = 0; playerIndex < players.size(); playerIndex++) {
            PlayerEntity player = players.get(playerIndex);
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                RoleFitService.SlotFit candidateFit = fit(player, slots.get(slotIndex), constraints);
                if (viable(candidateFit, constraints.minimumPositionScore())) {
                    fits[playerIndex][slotIndex] = candidateFit;
                    utilities[playerIndex][slotIndex] = utility(candidateFit, player);
                }
            }
        }
        int states = 1 << slots.size();
        long[][] scores = new long[players.size() + 1][states];
        int[][] parent = new int[players.size() + 1][states];
        short[][] chosenSlot = new short[players.size() + 1][states];
        for (int row = 0; row <= players.size(); row++) {
            Arrays.fill(scores[row], UNREACHABLE);
            Arrays.fill(parent[row], -1);
            Arrays.fill(chosenSlot[row], (short) -1);
        }
        scores[0][0] = 0;
        parent[0][0] = 0;

        for (int playerIndex = 1; playerIndex <= players.size(); playerIndex++) {
            for (int mask = 0; mask < states; mask++) {
                long previous = scores[playerIndex - 1][mask];
                if (previous == UNREACHABLE) continue;
                update(scores, parent, chosenSlot, playerIndex, mask, previous, mask, (short) -1);
                for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                    if ((mask & (1 << slotIndex)) != 0) continue;
                    if (fits[playerIndex - 1][slotIndex] == null) continue;
                    int nextMask = mask | (1 << slotIndex);
                    update(scores, parent, chosenSlot, playerIndex, nextMask,
                            previous + utilities[playerIndex - 1][slotIndex], mask, (short) slotIndex);
                }
            }
        }

        int bestMask = 0;
        for (int mask = 1; mask < states; mask++) {
            if (scores[players.size()][mask] > scores[players.size()][bestMask]
                    || (scores[players.size()][mask] == scores[players.size()][bestMask]
                    && Integer.bitCount(mask) > Integer.bitCount(bestMask))) {
                bestMask = mask;
            }
        }
        Map<Integer, Assignment> selected = new HashMap<>();
        int state = bestMask;
        for (int playerIndex = players.size(); playerIndex > 0; playerIndex--) {
            short slotIndex = chosenSlot[playerIndex][state];
            int previous = parent[playerIndex][state];
            if (slotIndex >= 0) {
                PlayerEntity player = players.get(playerIndex - 1);
                TacticDefinition.TacticSlot slot = slots.get(slotIndex);
                selected.put(slot.index(), new Assignment(
                        slot, player, fits[playerIndex - 1][slotIndex], false));
            }
            state = previous;
        }
        return selected;
    }

    private Map<Integer, Assignment> validateLocks(
            TacticDefinition tactic,
            Map<Long, PlayerEntity> playersById,
            Constraints constraints,
            List<String> warnings) {
        Map<Integer, Assignment> locked = new HashMap<>();
        Set<Long> usedPlayers = new HashSet<>();
        for (LockedAssignment lock : constraints.lockedAssignments()) {
            TacticDefinition.TacticSlot slot = tactic.slots().stream()
                    .filter(candidate -> candidate.index() == lock.tacticSlot()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown locked tactic slot: " + lock.tacticSlot()));
            PlayerEntity player = playersById.get(lock.playerUniqueId());
            if (player == null) throw new IllegalArgumentException(
                    "locked player is not in the supplied squad: " + lock.playerUniqueId());
            if (constraints.unavailablePlayerUniqueIds().contains(lock.playerUniqueId())) {
                throw new IllegalArgumentException("locked player is explicitly unavailable: " + lock.playerUniqueId());
            }
            if (locked.containsKey(lock.tacticSlot())) throw new IllegalArgumentException(
                    "tactic slot is locked more than once: " + lock.tacticSlot());
            if (!usedPlayers.add(lock.playerUniqueId())) throw new IllegalArgumentException(
                    "player is locked to more than one slot: " + lock.playerUniqueId());
            RoleFitService.SlotFit fit = fit(player, slot, constraints);
            if (Boolean.TRUE.equals(player.getInjured())) warnings.add("locked_player_injured:" + lock.playerUniqueId());
            if (!viable(fit, constraints.minimumPositionScore())) warnings.add(
                    "locked_player_below_position_threshold:" + lock.playerUniqueId());
            locked.put(slot.index(), new Assignment(slot, player, fit, true));
        }
        return locked;
    }

    private static void update(
            long[][] scores,
            int[][] parent,
            short[][] chosenSlot,
            int row,
            int state,
            long score,
            int previousState,
            short slot) {
        if (score > scores[row][state]) {
            scores[row][state] = score;
            parent[row][state] = previousState;
            chosenSlot[row][state] = slot;
        }
    }

    private static long utility(RoleFitService.SlotFit fit, PlayerEntity player) {
        long uid = player.getUniqueId() == null ? MAX_FM_UID : Math.min(MAX_FM_UID, player.getUniqueId());
        return Math.round(fit.overall() * 10) * 1_000_000_000_000L
                + value(player.getCa()) * 1_000_000L
                + (MAX_FM_UID - uid);
    }

    private static Map<Long, PlayerEntity> uniquePlayers(List<PlayerEntity> players) {
        Map<Long, PlayerEntity> out = new LinkedHashMap<>();
        if (players == null) return out;
        for (PlayerEntity player : players) {
            if (player == null || player.getUniqueId() == null) continue;
            out.merge(player.getUniqueId(), player, (left, right) ->
                    value(right.getWorldReputation()) > value(left.getWorldReputation()) ? right : left);
        }
        return out;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }

    private record FitKey(String snapshotId, String tacticFingerprint, Long playerUniqueId, int slot, int slotHash) {
    }

    public record LockedAssignment(int tacticSlot, Long playerUniqueId) {
    }

    public record Constraints(
            int minimumPositionScore,
            boolean includeInjured,
            Set<Long> unavailablePlayerUniqueIds,
            List<LockedAssignment> lockedAssignments,
            int alternativeLimit,
            String snapshotId,
            String tacticFingerprint) {
        public static Constraints defaults() {
            return new Constraints(15, false, Set.of(), List.of(), 3, null, null);
        }

        Constraints normalized() {
            return new Constraints(
                    Math.max(1, Math.min(20, minimumPositionScore)),
                    includeInjured,
                    unavailablePlayerUniqueIds == null ? Set.of() : Set.copyOf(unavailablePlayerUniqueIds),
                    lockedAssignments == null ? List.of() : List.copyOf(lockedAssignments),
                    Math.max(0, Math.min(10, alternativeLimit)),
                    snapshotId,
                    tacticFingerprint);
        }
    }

    public record Assignment(
            TacticDefinition.TacticSlot slot,
            PlayerEntity player,
            RoleFitService.SlotFit fit,
            boolean locked) {
    }

    public record Alternative(
            PlayerEntity player,
            RoleFitService.SlotFit fit,
            Integer assignedTacticSlot) {
        public boolean disruptsAnotherSlot() { return assignedTacticSlot != null; }
    }

    public record Result(
            List<Assignment> assignments,
            List<TacticDefinition.TacticSlot> unfilledSlots,
            Map<Integer, List<Alternative>> alternatives,
            double totalScore,
            double averageScore,
            List<Integer> bottleneckSlots,
            List<String> warnings) {
        public Assignment assignmentFor(int tacticSlot) {
            return assignments.stream().filter(value -> value.slot().index() == tacticSlot).findFirst().orElse(null);
        }
    }
}
