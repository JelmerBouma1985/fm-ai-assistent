# AGENTS.md — fm-ai-assistent

Local companion app for Football Manager 2026 (Windows 11 and Linux).
Reads the loaded FM26 save from RAM, stores a local snapshot, and offers
browsing plus AI-assisted recruitment/tactics advice.

## Stack

- Java 25 (enforced `[25,26)` by `maven-enforcer-plugin`), Spring Boot, Vaadin 25.
- UI is built in Java (Vaadin); no separate frontend code. Frontend CSS lives in
  `src/main/frontend/styles/`.
- H2 file database + Liquibase (`src/main/resources/db/changelog/`). JPA entities
  in `domain.entity`, queries via Spring Data repositories + specifications.
- FM26 RAM access via JNA (`memory`, `linux`, `windows`, `exporter` packages).
- AI agents: local Codex / Antigravity / Copilot CLIs plus OpenRouter HTTPS.
  MCP tools in `mcp` expose FM data to the agents.

## Build, test, verify

- Fast tests: `./mvnw -Dvaadin.skip=true test` (requires JDK 25, `JAVA_HOME` set).
- Full gate before a PR: `./mvnw clean verify` (compiles backend, runs unit +
  integration + architecture tests, builds production Vaadin frontend, JAR,
  JaCoCo report, CycloneDX SBOM).
- Coverage minimums (enforced): 45% line, 30% branch.
- Focused runs: `./mvnw -Dvaadin.skip=true test -Dtest='XTest,YTest'`.
- Boot smoke test: `./mvnw -Dvaadin.skip=true spring-boot:run`, then
  `curl http://127.0.0.1:8080/actuator/health` (expect `UP`) and `/` (expect 200).
  Stop the instance afterwards so port 8080 is free.

## Architecture rules (enforced by ArchUnit)

- `domain` must not depend on infrastructure or delivery layers
  (`repository`, `service`, `mcp`, `web`, `linux`, `windows`).
- `exporter` (native/RAM code) must not depend on persistence or delivery layers.
- Non-`web` code must not depend on Vaadin or `web` adapters.
- Check `ArchitectureTest.java` when adding cross-package dependencies.

## Code conventions

- Commit messages in English. PR descriptions state that AI-agent-written code
  was produced by `Muse Spark 1.3 Contributor (opencode-go/muse-spark-1.3-contributor)`
  via OpenCode, reviewed and tested by the repository owner.
- No emojis in code, UI strings, or communication unless requested.
- When referencing code, use `path:line` citations.
- Prefer editing existing files over creating new ones. Never commit build
  artifacts — notably `src/main/bundles/dev.bundle`.
- Vaadin 25 API notes: `Span` has no `setTooltipText` (use the `title`
  attribute); poll listeners are registered on `UI` (`ui.addPollListener`),
  not on components.
- Constructor injection everywhere; add new collaborators as constructor
  parameters and update the corresponding `*Test.view()` factory methods.

## Data-access and UI patterns

- RAM exports go through `exporter/*Exporter` → `SnapshotDatabaseWriter` inside
  one transaction in `DatabaseLoadAllService.loadAll` (atomic snapshot swap).
- Long-running loads report progress via `setPhaseListener` on
  `DatabaseLoadAllService`, surfaced through `RefreshCoordinator.phase()`.
  Only one refresh runs at a time (single-thread coordinator).
- New `tactic_context`-style persistence needs a Liquibase changeSet
  (`changes/NNN-*.xml` + include in `db.changelog-master.xml`); old rows must
  keep working (nullable new columns, null-tolerant restore paths).
- System properties override auto-detected paths, e.g.
  `-Dfmaiassistent.tactics.directory=...` and
  `-Dfmaiassistent.shortlists.directory=...`. Always provide one.
- Secrets (OpenRouter API keys) live only in backend session memory —
  never in the database, browser storage, or logs.

## Lessons from code review (apply to every change)

1. **Bounded reads.** Never allocate a whole file without a size cap
   (`InputStream.readNBytes(limit + 1)`); reject oversized files before a full
   copy exists in memory — including restore-from-disk paths at startup.
2. **Publish after persist.** In-memory state (`AtomicReference.current`)
   must be updated only after `repository.save()` succeeded, so chats never
   see unsaved data.
3. **No absolute local paths in AI prompts.** Markdown sent to providers
   contains file names only; absolute paths stay in the database and local UI.
4. **Append warnings, never replace them.** Truncation or fallback notes must
   preserve earlier warnings (e.g. cached-copy notices).
5. **Parse once.** Pass parsed results (metadata, fingerprints) through
   persist/publish steps instead of re-parsing (decrypt + decompress + decode).
6. **Manifest wins over stale folders.** Steam library resolution prefers the
   library holding the game (`appmanifest_<id>.acf`); native `Documents`
   keeps first precedence; other existing folders are last resort.
7. **New RAM offsets need an anchor and a drift test.** Offsets go in the
   layout record (`memory`), never as scattered constants; every new offset
   needs a range anchor in `PlayerSnapshotValidator` plus a test decoding the
   same fixture with a shifted layout that validation must reject.

## Maintenance of this file

Every merged PR with a cross-cutting lesson appends a concise rule here
(preferably with the same shape as the list above). Propose additions;
the repository owner approves.
