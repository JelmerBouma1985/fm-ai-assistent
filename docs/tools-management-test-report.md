# Tools management test report

Date: 2026-11-20. Branch: `2.0.6`. Actual host: Windows 11 Pro, version 10.0.26200, x64. JDK: GraalVM CE OpenJDK 25.0.2. Implementation and automated review performed by OpenAI Codex using repository commands and local browser CDP; repository-owner review remains pending. No release, tag or push was performed.

The assignment's specification is present locally as `docs/TOOLS_MANAGMENT_SPEC.md` (filename spelling differs from the requested `TOOLS_MANAGEMENT_SPEC.md`). All milestones A–F were implemented against that document.

## Final results

Raw logs and screenshots are retained locally in the ignored directory `analysis/tools-management-evidence-20261120/`. They contain test metadata and an empty isolated application, not a real FM snapshot or credentials. This committed report summarizes the results; the [guide](tools-management.md) supplies reproduction steps on both platforms.

| Platform | Command/test | Result | Evidence | Notes |
|---|---|---|---|---|
| Linux | `./mvnw -Dvaadin.skip=true test` | NOT RUN | `linux-availability.txt` | No Linux host available; `wsl --list --quiet` reports WSL is not installed. |
| Linux | `./mvnw clean verify` | NOT RUN | Same availability evidence | Windows executions are not claimed as Linux results. |
| Linux | Interactive `/tools` | NOT RUN | Manual procedure in guide | Requires a Linux application/browser session. |
| Linux | MCP disabled-call denial | NOT RUN | Real-server automated test is portable but was executed on Windows only | No inferred Linux pass. |
| Windows 11 | `.\mvnw.cmd '-Dvaadin.skip=true' test` | PASS | `windows-tests.log` | Final suite: 658 tests, zero failures/errors, 16 skipped. |
| Windows 11 | `.\mvnw.cmd clean verify` | PASS | `windows-clean-verify.log` | 658 tests, zero failures/errors, 16 skipped; production Vaadin frontend, packaged JAR, SBOM and coverage checks pass. |
| Windows 11 | `/tools` keyboard/button in packaged browser | PASS | `windows-browser.log`, `windows-tools.png` | Actual headless Chrome with keyboard input and DOM interactions; not a claim of human manual testing. No API key or provider request. |
| Windows 11 | MCP disabled-call denial | PASS | `McpProtocolCompatibilityTest`, `windows-browser.log` | Browser toggle removes discovery; cached/direct call returns `isError=true` and `tool_disabled`; re-enable restores execution. |
| Windows 11 | Live Activity and narrow window | PASS | `windows-activity.png`, `windows-tools-narrow.png`, `windows-browser.log` | Genuine external denial appears automatically; 640×800 viewport retains detail controls; zero browser runtime exceptions. |
| Windows 11 | Packaged startup / HTTP smoke | PASS | `windows-smoke.log` | Production mode, `/actuator/health` = `UP`, `/` = HTTP 200 on isolated port 18081. |
| Windows 11 | Human manual walkthrough with a loaded FM save | NOT RUN | Guide checklist | No FM process/save was touched for feature verification. |
| Windows 11 | Live authenticated Codex/Antigravity/Copilot/OpenRouter subscription | NOT RUN | Existing deterministic CLI route tests and mocked OpenRouter tests pass | No paid service, real key or CLI login used. |

658 includes 642 successful executed tests and 16 conditional/opt-in skips. The skipped native/local integration checks remain explicitly skipped; they are not counted as successful live verification. Coverage measured by the full gate: **68.26% line**, **49.77% branch**, above the enforced 45%/30% minimums.

## Automated coverage

- **Catalog:** compares actual tool registrations, schemas and required/nested fields; automatic discovery; duplicate callbacks/metadata; schema/description/catalog bounds; unknown-effect conservative defaults; all 23 current effects audited.
- **Policy/settings:** first-run defaults; global/conversation intersection; stale callbacks; category/preset/custom selection; 64 KiB policy cap and 512 KiB settings cap; restart persistence; invalid/future JSON; malformed/oversized backing preferences; byte-preserving recovery copies; publish-after-save; concurrent individual/bulk updates; listener isolation.
- **Actual MCP boundary:** real random-port Spring server, both `2025-11-25` and `2025-06-18` clients, schema equality, dynamic `tools/list` removal/re-add, cached/direct disabled-call denial with zero handler interactions, unattended Ask denial, invalid schema arguments, and an actual `notifications/tools/list_changed` SSE event.
- **Actual OpenRouter loop:** local HTTP/SSE mock inspects outgoing filtered schemas, returns forged/stale calls after a global/conversation deny, verifies no handler invocation, re-enables successful execution, checks response/conversation event attribution, disconnect cleanup and ordinary chat for a model without tool support. Existing credential redaction, budgets, cancellation, result limits, model selection and paid-mode confirmation tests remain passing.
- **Vaadin UI:** exact whitespace/case `/tools`, suggestions/mouse/keyboard, zero sends/messages, prose/unknown-command fallbacks, existing prompt suffixes, all four provider routes; actual dialog scope/search/status/category controls, presets/custom names/reset, details/schema/HTML-as-text, forced remote-write rejection, cross-tab atomic edits and expired conversations, background trust, listener cleanup, real activity panel and session isolation.
- **Events/privacy:** unique call IDs, observed statuses/durations, safe validation-versus-execution failures, interruption/cancellation, external null conversation IDs, 200/500 bounds, clear without resurrection, no raw input/result/key/chat data, and no cross-session history.
- **Repository regressions:** existing backend, memory-fixture, desktop, manager/fixtures, CLI route, integration and ArchUnit checks included in the full suite.

## Packaged browser checks

An isolated headless Chrome profile used the production JAR with an in-memory H2 database, a separate preferences file, and all native agent integrations disabled. The browser used no OpenRouter API key. Checks passed for:

1. `/tools` + Enter opens the real dialog and clears the input with zero chat messages.
2. Browser catalog contains all 23 registered tools with normal Allow defaults.
3. The packaged MCP server exposes the same catalog.
4. Disabling a metadata tool in the browser removes it from real MCP discovery and denies a cached direct call.
5. Activity automatically shows the genuine denial with external/uncorrelated ownership.
6. Re-enabling restores discovery and successful read-only metadata execution without game data.
7. Details show the exact copyable API name and actual required input schema.
8. Detail controls remain inside a 640×800 viewport; internal scrolling works.
9. Presets show an editable custom name; the Tools button closes/reopens with saved policy.
10. No browser runtime exceptions.

Development verification uncovered and fixed an SSE test-handshake assumption and a real dialog subscription timing bug: `Dialog.open()` can fire before automatic UI attachment. The final UI registers through the opening UI and has a regression test for that lifecycle. The final browser run confirms live activity rather than requiring manual refresh.

## Scope and remaining manual verification

Global policy is enforced for all MCP clients and OpenRouter. Conversation selection is enforced for embedded OpenRouter only; CLI/MCP traffic has no invented browser-chat identity. Ask safely returns `approval_required` immediately because current integrations do not securely pause/resume a concrete call. There is no fake approval popup or indefinite pending queue.

Tools remain read-only with respect to Football Manager. The only existing effects are the app's snapshot refresh, persistent recruitment evidence updates, and new local shortlist exports. No game-write capability was added.

Linux execution, human walkthroughs, real-save availability transitions and live authenticated provider sessions remain manual verification items. They are not represented as implemented feature gaps or as completed platform testing. Follow the guide's Windows/Linux procedure before a later release. Test processes use isolated data and are stopped after verification; generated bundles and build artifacts are excluded from commits. Existing user changes in release notes and the local shortlist file are preserved.
