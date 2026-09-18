# FM tool response size audit

The tests serialize synthetic, representative JSON fixtures and record UTF-8 byte counts only. No FM records or credentials are stored. Bytes are a consistent comparison for these fixtures, not an exact token count for any model. The compact column applies the shared seven-tool projection; all other tools keep their original result.

| Tool | Full bytes | Compact bytes |
| --- | ---: | ---: |
| `fm26_find_clubs` | 1,965 | 1,965 |
| `fm26_find_players` | 808 | 380 |
| `fm26_get_club_context` | 807 | 310 |
| `fm26_get_player_details` | 1,233 | 1,233 |
| `fm26_find_staff` | 822 | 378 |
| `fm26_get_staff_details` | 1,177 | 1,177 |
| `fm26_get_staff_coaching_roles` | 719 | 719 |
| `fm26_get_role_attributes` | 821 | 364 |
| `fm26_transfer_shortlist` | 741 | 741 |
| `fm26_create_shortlist_file` | 697 | 697 |
| `fm26_get_data_status` | 712 | 712 |
| `fm26_refresh_data` | 696 | 696 |
| `fm26_analyze_squad` | 1,235 | 735 |
| `fm26_optimize_lineup` | 899 | 374 |
| `fm26_recruit_for_tactic_slot` | 741 | 741 |
| `fm26_compare_players` | 732 | 732 |
| `fm26_find_replacements` | 741 | 741 |
| `fm26_plan_squad_moves` | 1,826 | 1,119 |
| `fm26_update_recruitment_case` | 711 | 711 |
| `fm26_get_recruitment_board` | 723 | 723 |

The seven projected fixtures total 7,218 bytes in full and 3,660 bytes compact, a 49.3% reduction. The `tools/list` catalog is 23,952 bytes before adding `responseDetail` and 24,918 bytes after. The search default changing from 50 to 20 rows provides further savings on broad searches; that effect is separate from the same-fixture comparison above. The audit does not justify shortening descriptions or tactic context in this change.
