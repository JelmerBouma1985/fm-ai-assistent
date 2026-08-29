# Release Notes

## Next release

- Adds direct FM26 staff extraction from the loaded save, including jobs, clubs, contracts, CA/PA, reputation and staff attributes in English
- Adds a dedicated Staff tab with filters for identity, job, club, division, ability, reputation, salary, contract expiry and individual attributes, plus a Coaching roles filter tab with independent minimum-star requirements for all nine assignments
- Calculates FM26 ratings for all nine training assignments from their exact role-specific attribute weights, including the new Authority and singular Goalkeeping attributes
- Shows each staff member's best coaching assignment in the Staff table and a complete role breakdown with 0-20 score, quality tier, stars and weighted attributes in their profile
- Adds `fm26_find_staff`, `fm26_get_staff_details` and `fm26_get_staff_coaching_roles` MCP tools so AI agents can search, rank and explain staff decisions by authoritative FM Unique ID and coaching-role fit
- Speeds up data refreshes by extracting players, staff, clubs and competitions from FM26 RAM concurrently and replacing Hibernate's per-entity snapshot writes with bounded JDBC batches, while retaining rollback-safe atomic replacement

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
