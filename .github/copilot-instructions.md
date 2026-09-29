# Copilot code review instructions — fm-ai-assistent

Review every pull request against this checklist, in addition to general
correctness, security, and performance review. Flag violations inline with
file/line references. Full project conventions live in `AGENTS.md`.

## Must-check

1. **Bounded I/O.** Any file read must enforce a size cap before a full copy
   exists in memory — including startup restore paths, not just interactive
   uploads. Unbounded `readAllBytes` is a finding.
2. **Persist before publish.** In-memory state exposed to chats/UI must only
   be updated after the corresponding `repository.save()` succeeded. A failed
   save must leave the previous state untouched.
3. **No local-path disclosure.** Strings forwarded to external AI providers
   (OpenRouter, Copilot, Codex, Antigravity) must not contain absolute local
   paths, usernames, or mount names. File names only; absolute paths stay in
   the database and local UI.
4. **Warnings accumulate.** New warnings (truncation, fallback, expiry) must
   be appended to existing ones, never replace them.
5. **No duplicate expensive work.** FMF archives (decrypt + decompress +
   decode) and RAM snapshots must be parsed once per operation; pass results
   through persist/publish steps.
6. **Path precedence.** Steam library resolution order: explicit system
   property override → existing native `Documents` folder → library holding
   the game (`appmanifest_<id>.acf`) → other existing folders → native
   default. A stale folder must never beat the manifest-backed library.

## Should-check

- New Liquibase changeSets are backward compatible (nullable columns,
  null-tolerant restore, include registered in `db.changelog-master.xml`).
- New service behavior has unit tests with mocked repositories (Mockito
  style used in `*Test`); constructor changes update `*Test.view()` factories.
- No new dependencies from `domain` to infrastructure/delivery layers, from
  `exporter` to persistence/UI, or from non-`web` code to Vaadin
  (see `ArchitectureTest.java`).
- No secrets in code, logs, or persisted state; no build artifacts
  (e.g. `src/main/bundles/dev.bundle`) in the diff.
- Vaadin 25 APIs used correctly (`title` attribute instead of
  `Span.setTooltipText`; poll listeners on `UI`).
