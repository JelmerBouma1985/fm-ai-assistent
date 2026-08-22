# Release Notes

## Next release

## 1.1.0
### Most important

- Ask the AI assistant to create FM26 player shortlist `.fmf` files from recommended players, ready to import directly into Football Manager 26
- Adds snapshot IDs, live game-date freshness checks and safe transactional RAM refreshes
- Adds squad diagnosis across both in-possession and out-of-possession tactic roles
- Adds a global tactic-aware lineup optimizer that assigns each player to at most one slot, supports locked and unavailable players, and reports unfilled slots and squad bottlenecks
- Adds tactic-slot recruitment that applies literal hard filters across both phases before ranking targets by tactical fit, affordability, willingness and projected improvement to the optimized XI
- Adds FM Unique ID based player comparisons and reference-player replacement searches
- Requires replacement searches to use the incumbent's explicit deployed position or tactic slot instead of guessing a position
- Adds a read-only incoming/outgoing squad-move planner with explicit unknown fees and wages
- Adds persistent uploaded tactic context with a stable fingerprint and automatic restoration after restarting the application
- Adds a persistent, career-scoped recruitment board for verified interest, deal stages, fees and wage quotes; evidence expires after 30 in-game days by default
- Improves player and club filtering, deterministic sorting, pagination and compact responses
- Speeds up repeated full-database tactical searches with cached player-slot fits and indexed role profiles
- Keeps explicit transfer fee ceilings literal and reports when sales are needed instead of silently shrinking the search to the current budget

### Bug fixes

- UI now shows correct position AML and AMR which were inverted
