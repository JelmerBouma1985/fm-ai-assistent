# Website implementation inventory

Prepared from the repository state on 10 October 2026.

## Verified application features

- Windows 11 and Linux are the documented platforms.
- The user starts FM26, loads a save and selects **Load data**; the result is an atomic local snapshot, not continuous synchronization.
- The UI browses and filters players, staff, clubs and competitions, including player football/contract/financial data and staff coaching-role ratings.
- Exported FM26 `.fmf` tactics can be decoded locally and enabled as assistant context.
- Documented decision workflows include squad-depth analysis, unique-lineup optimization, role recruitment, replacements, player comparison, move planning, recruitment evidence and shortlist-file creation.
- Embedded AI options are Codex, Antigravity, GitHub Copilot and OpenRouter. External provider privacy, accounts, limits and potential costs apply.
- Packaged desktop, native and Java JAR distributions are documented. The JAR requires Java 25 or newer.

Evidence: root `README.md`, `RELEASE_NOTES.md`, `pom.xml`, current UI screenshots and existing Maven release workflow. The public GitHub Releases page showed v1.2.0 as latest during the audit; the checkout is a 1.3.0-SNAPSHOT with unreleased 1.3.0 notes, so visitor download links use `/releases/latest` instead of claiming 1.3.0 is published.

## Screenshots used

Exact PNG copies are used so the website captures remain pixel-identical to the genuine repository screenshots:

- `screenshots/gui-players.png` → `public/images/players.png`
- `screenshots/gui-ai-assistant.png` → `public/images/ai-assistant.png`
- `screenshots/ai-context.png` → `public/images/ai-context.png`
- `screenshots/gui-player-dialog.png` → `public/images/player-details.png`
- `screenshots/gui-filter-dialog.png` → `public/images/player-filters.png`
- `screenshots/gui-recruitment.png` → `public/images/recruitment.png`
- `screenshots/gui-staff.png` → `public/images/staff.png`
- `screenshots/gui-header-freshness.png` → `public/images/freshness.png`

The captures show simulated/in-game database records but no visible API keys, account credentials, private filesystem paths or personal contact details.

## Assumptions and unknowns

- No license file exists, and root `package.json` says `UNLICENSED`; visitor copy does not describe the project as open source.
- Release asset names were not hard-coded because they can change. Downloads resolve through the owner-controlled GitHub Releases pages.
- Provider availability, model catalogs, prices and CLI setup can change. The site links back to the current repository documentation.
- The website is not claimed live until the Pages workflow, repository settings, DNS and HTTPS are confirmed by the owner.

## Files created or changed

- Created isolated Astro project, reusable components, styles, all mandatory content pages and deterministic build validation under `website/`.
- Created real branded favicon/social art and optimized copies of reviewed product screenshots.
- Added Pages deployment and pull-request website validation workflows.
- Updated root ignore rules for website build state and added a small website link to the root README.

## Tests performed

- `npm ci` — passed; 276 packages audited, 0 vulnerabilities reported.
- `npm run check` — passed with 0 errors, 0 warnings and 0 hints across 27 files.
- `npm run build` — passed; 14 HTML pages plus sitemap were generated.
- `scripts/validate-build.mjs` — passed for 13 indexable routes, unique metadata, H1s, canonicals, JSON-LD, sitemap entries, placeholders and local links/assets.
- Parsed all four repository workflow files as YAML with PyYAML — passed.
- Served `website/dist` locally and requested all required pages plus `robots.txt`, `sitemap.xml` and `404.html` — every request returned HTTP 200.
- `git diff --check` — passed.
- Reviewed all selected source screenshots for credentials, private paths and contact details; none were visible.
- Browser automation and Lighthouse were not available in this environment, so viewport interaction and numeric Lighthouse scores remain unmeasured.

## Remaining setup

The repository owner must enable GitHub Actions as the Pages source, verify and configure only `fmaiassistant.com`, update DNS, confirm the deployment, enable HTTPS and submit the sitemap to Google Search Console and Bing Webmaster Tools. Exact steps and verification commands are in `website/README.md`.
