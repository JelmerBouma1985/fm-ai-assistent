# Spike notes: player performance hunt (FM26 RAM)

Goal: locate per-player performance data (apps, goals, assists, ratings) in RAM.

## Ground truth (read from FM UI + app DB snapshot, Real Madrid save)

| Player | unique_id | Apps | Goals | Assists | Recent ratings |
|---|---|---|---|---|---|
| Kylian Mbappé | 85139014 | 7 | 5 | 2 | 7.30 (Valencia), 6.30 (Atlético), 7.20 (Bayern) |
| Arda Güler | 2000077193 | 7 | 4 | 3 | 6.60 (Valencia), 6.80 (Atlético), 9.20 (Bayern), 9.20 (Portugalete), 8.80 (Gil Vicente) |
| Vinícius Júnior | ? | ? | ? | ? | ? |

Güler last-5 average 8.12 = (6.6+6.8+9.2+9.2+8.8)/5 exactly.
Güler LaLiga split: 2 apps, 0 goals, 0 assists, 6.70 rating.
Vinícius LaLiga split: 1 goal, 1 assist, 7.00 rating (apps unknown).

Verified records (same session): Mbappé 0x260e93b18, Güler 0x261403318.
Verified team: Real Madrid first team 0x252f396b0 (captain Carvajal +0x88,
vice-captain Valverde +0x90); second team 0x2534bb498 (youth captains).

## Ruled out (negative scans, all implemented in TeamSheetSpikeTest)

- {apps,goals,assists} u16 co-occurrence within +-32KB of either record.
- Rating float32 near records, pointer targets, or heap-wide with neighbours
  (4.6 GB scanned for both players' triples).
- Rating float64 heap-wide with neighbours (Güler triple).
- Lone 6.3 float elsewhere: coincidence (siblings absent).
- Lone 6.3 float + 0x6fff-region int clusters: MACHINE CODE, not data
  (immediate constants 100/1000/5000/..., FP constants embedded).
- No person records within +-0x80 of tactic-name hits; no dense person
  arrays one hop from the team record; no known unique_ids within +-2MB
  of team or tactic structs.

## Open lead (unattributed)

Small-int block near 0x25813fdde (addresses churn; re-locate per session):
`[4,7,7,67,4,67,62,3]` = Güler-exact 7/4/3 plus 67 (~LaLiga 6.70?) with
shirt-number-range neighbours. No adjacent person pointers, so unattributable.
No analogous block found for Mbappé (weakens but does not kill it).

## Next methods (in priority order)

1. Time-differential: dump candidate regions, play a match with a goal,
   re-dump, find +1 fields. Strongest available method.
2. Scaled-int variants (u8/u16 x10/x100 of ratings) around records.
3. Per-competition stats table discovery (LaLiga splits must match exactly
   per player: Güler [2,0,0,6.70], Vinícius [?,1,1,7.00]).
4. Match-object/fixture RE (ratings may live per fixture, aggregated on display).
