# Tools management

`/tools` is a local command in FM AI Assistent's chat composer. It opens the same dialog as **Tools** beside **Context**, clears the command and makes no AI request. The suggestions menu supports mouse, touch, arrows, Enter and Escape. Surrounding whitespace and case are ignored for the exact `/tools` command; discussing it in a sentence or adding an argument does not navigate. Existing prompt shortcuts retain their behavior.

## Walkthrough

1. Open **AI Assistent**, then **Tools**. Opening it while a response runs is safe.
2. Choose **Global MCP settings** or, with an active OpenRouter chat, **This conversation**. The scope selector remains visible across all tabs. The dialog identifies the conversation it edits; switching chats does not silently retarget an already-open dialog.
3. In **Tools**, search titles, IDs, descriptions or categories. Filter enabled, disabled or unavailable tools. Each category shows an enabled count and bulk controls.
4. Select **Details** to read the exact MCP name, description, category, requirements, effect, effective access, recent observed usage and expandable JSON Schema. The name can be selected/copied. Schemas retain nested properties, required fields and descriptions; all content is rendered as text.
5. In **Presets**, choose a preset and select **Apply preset**. The selected scope is shown above the tabs. Save the current selection under a custom name (1–60 characters without control characters) to replace the one editable custom preset.
6. **Reset to defaults** restores Allow for classified tools and Disable for unclassified future tools in the selected scope. It does not erase the saved custom preset. Presets and resets do not execute tools.
7. In **Activity**, choose the current conversation or this session plus external MCP calls, then filter status, category, time and event count. Clear session history when needed. External history and global changes are available only to loopback browsers.

The dialog scrolls internally on small windows. Controls have labels, tabs use Vaadin's keyboard navigation, and availability uses text rather than color alone.

## Permissions and provider scope

| Provider/path | Global policy | Conversation selection | Observed calls |
|---|---|---|---|
| OpenRouter in embedded chat | Enforced | Enforced for that conversation | Conversation and response IDs are known |
| Embedded Codex CLI | Enforced at MCP server | Not reliably correlated; global controls only | MCP calls are external/global; existing native chat events remain |
| Embedded Antigravity CLI | Enforced at MCP server | Not reliably correlated; global controls only | Same limitation |
| Embedded Copilot SDK/CLI | Enforced at MCP server | Not reliably correlated; global controls only | Same limitation; native permission UI is preserved |
| Other MCP clients | Enforced | No browser conversation assumed | External/global only |

For OpenRouter, execution requires global Allow **and** conversation Allow **and** satisfied availability. Ask takes precedence over Allow; Disable takes precedence over both. A conversation preset cannot override a global restriction. Policies are checked when each invocation begins; an already-running handler may finish after a disable. Policy changes and category updates are atomic, including independent edits from multiple tabs.

MCP `tools/list` omits globally disabled tools using MCP SDK 2.0.0 dynamic registration. Its supported `notifications/tools/list_changed` notification is emitted when registrations change. Clients without an open server event stream may need to refresh/reconnect; the SDK can log an unavailable notification stream for those clients. Neither stale schemas nor cached callbacks authorize execution: HTTP requests to removed tools return a safe MCP `isError=true` denial, and callbacks independently recheck current policy. Other registration paths are not left unwrapped.

OpenRouter builds tool definitions from the same registered schemas on every model request, filtering effective policy and inexpensive availability. A forged or stale denied call stops the turn with a readable reason without executing its handler, preventing a denial/retry loop. Models explicitly reported without tool support can answer ordinary chat with no tool definitions; Tools displays that limitation. Free/Auto routers retain their existing routing semantics.

Global mutation checks use the actual servlet peer address, not forwarded headers, and reject non-loopback requests. Vaadin's normal session and CSRF mechanisms remain in place. Preferences are never modified through a new REST endpoint. Opening Tools does not rewrite external agents' configuration or grant their native permissions.

### Allow, Ask and Disable

Classified tools default to **Allow**, preserving normal use. Read-only tools offer Allow/Disable and never add mandatory approval popups. Local app/file effects also offer **Ask**. Current integrations cannot securely pause and resume a concrete invocation for browser approval; Ask therefore returns `approval_required` immediately. It never silently allows, waits indefinitely, or sends approval to an arbitrary chat. Review the tool's effect, choose Allow if appropriate, and retry in a new turn/chat as directed. This is a policy change, not an approval of a previously queued call. There is no pending approval queue or fake approval button.

## Audited effects and requirements

All 23 current tools are discovered from actual registrations. Twenty are read-only queries, metadata lookups or analyses. In particular, transfer shortlists and squad move plans are analyses, not in-game transactions.

| Tool | Demonstrated effect |
|---|---|
| `fm27_refresh_data` | Reads FM RAM and refreshes FM AI Assistent's own local snapshot |
| `fm27_update_recruitment_case` | Updates the app's persistent recruitment evidence, requiring loaded players and career identity |
| `fm27_create_shortlist_file` | Creates a new exported `.fmf` shortlist for manual import, requiring loaded player data |
| Other current tools | Read/query/analyze existing data or metadata |

**No tool writes to Football Manager memory, game saves, player attributes or transfers.** An exported shortlist is a new local export, not a modified save.

Availability is independent of effects and permission. Snapshot-required tools show **Needs game data**; lineup optimization and tactic-slot recruitment require a loaded tactic; fixture tools require an employed club manager; recruitment evidence updates also require career identity. General searches do not require the selected manager to be employed. Metadata tools, data status and refresh can be used before a snapshot is loaded. Availability queries read snapshot metadata and current context only; painting the UI never scans RAM, probes the live game, runs a tool or contacts a provider.

## Presets

| Stable ID | Label | Selection |
|---|---|---|
| `all` | All available | All classified discovered tools; availability/global restrictions still apply |
| `scouting` | Scouting | Scouting and recruitment categories |
| `tactics` | Tactical analysis | Tactics and squad categories |
| `staff` | Staff | Staff category |
| `custom` | User's custom name | Saved selection intersected with the current catalog |

Selections are derived from tool metadata, never another hardcoded tool list. Removed or renamed IDs in a saved selection are ignored when applying it. New annotated tools appear automatically; tools without effect metadata appear as **Unclassified effect** and default to disabled. Changing a preset is reversible by another preset, individual/category edits or reset.

## Execution history and privacy

The shared gateway emits RUNNING followed by SUCCEEDED, FAILED, DENIED or CANCELLED only when observed. It records UUID, tool ID/category, source, reliably known conversation/response IDs, start time, duration and a fixed safe summary. A removed disabled MCP tool receives a denial event at the HTTP boundary without entering the SDK/handler. The callback and transport never create two events for one invocation.

History is a thread-safe in-memory buffer, limited to 200 events per conversation and 500 application-wide. OpenRouter renders discrete activity below its chat, collapses activity after eight recent rows, and restores it when reopening that conversation in the session. External calls have no fabricated chat ID. Other sessions' conversation events never appear in a browser's history. Handler errors are classified but their raw messages are not recorded or exposed; no argument/result previews, player records, keys, recruitment text, prompts, tokens or absolute paths enter this feature's history or settings.

Disconnect/session expiry clears that session's conversations, selections and observed history. External history ends on application restart or explicit clearing and is available only from loopback access. Clear/evicted running events do not reappear on completion. UI listeners close on detach/close; coalesced background updates avoid an unbounded Vaadin task queue. An expired conversation produces a notice instead of silently editing another chat.

## Architecture and extension

| Responsibility | Existing/new implementation |
|---|---|
| Registration | `config/FmAiAssistentMcpConfiguration`: `MethodToolCallbackProvider` over the four existing handler beans, then existing compact-schema decoration, then `ToolRegistry` |
| Actual handlers | `mcp/FmAiAssistentTools`, `FmDecisionTools`, `FmSnapshotTools`, `FmFixtureTools`; existing application services retain business logic |
| Canonical catalog and execution | `tools/ToolRegistry`; final callback definitions are the source for UI, MCP and OpenRouter |
| Metadata | `tools/ToolInfo` on the same annotated tool methods; category, effect and real requirements |
| Policy/settings | `tools/ToolPolicy`, existing `service/AppSettingsService`; constructor injection and atomic preference writes |
| Availability | `tools/ToolAvailability`; metadata/context reads only |
| Events | `tools/ToolActivity`; Vaadin-independent bounded metadata buffer |
| MCP discovery and enforcement | `mcp/McpToolPolicyVisibility`, `McpToolArgumentsFilter`, plus policy-wrapped callbacks |
| OpenRouter | Existing `openrouter/OpenRouterConversationService`: per-conversation immutable selections, schema filtering, execution gateway, response IDs |
| CLI integrations | Existing Codex/Antigravity/Copilot services/process/session managers and endpoint configuration remain intact |
| Local command/UI | Existing `web/ui/ChatCommands`, `ChatCommandPicker`, all provider views, `AiAssistantView`; `ToolsDialog` and `ToolActivityPanel` |
| Session/privacy lifecycle | Existing `web/ui/OpenRouterSession` remains Vaadin-session-scoped and closes the backend service on destruction |

To extend metadata, add `@ToolInfo` beside the handler's existing `@Tool`, choosing the actual category/effect and only unconditional requirements. Register tools through the existing provider. Do not add another UI list or bypass the wrapped provider. Keep schema generation/compact decoration in the shared registration path. Startup rejects duplicate tool/metadata IDs, malformed/non-object/null schemas and oversized catalogs. Limits: 256 tools, 64 KiB schema characters per tool, 512 KiB aggregate schema characters, 16 KiB description characters per tool; JSON parsing additionally uses Jackson's constraints.

### Preferences format and recovery

The existing `fm-ai-assistent.properties` contains one `tools.policy` property holding versioned JSON:

```json
{"version":1,"customName":"My selection","access":{"fm27_refresh_data":"ASK"},"custom":["fm27_find_players"]}
```

Only policy data is stored. Missing overrides preserve defaults. Conversation choices, API keys, chat/tool data and history are not persisted. Custom/global sets are capped at 256 IDs each; IDs and names are validated; encoded policy is capped at 64 KiB characters. The entire properties file is bounded to 512 KiB before decoding. Updates preserve currency and other settings, write a temporary file and replace atomically where supported. In-memory policy is published only after save succeeds.

Use the existing override `-Dfmaiassistent.settings.file=<isolated-path>` for tests or custom deployment. Invalid JSON, unsupported versions, malformed properties or an oversized backing file use defaults with a visible non-fatal Tools notice. The original is untouched on read. The first successful repair keeps a sibling `.recovery-<UUID>` copy; saving must succeed before live policy changes. A user can restore that copy while the app is stopped. Unknown saved IDs do not create registrations or authorize calls to unknown tools.

## Verification on Linux and Windows

Use JDK 25 and the Maven wrapper. Core tests require no running FM process, CLI login, OpenRouter subscription or real API key. Keep profiles/data isolated and never delete a real user profile.

Linux:

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw -Dvaadin.skip=true test
./mvnw clean verify
./mvnw -Dvaadin.skip=true spring-boot:run
# In another terminal:
curl -fsS http://127.0.0.1:8080/actuator/health
```

Windows PowerShell (quote Maven properties):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\mvnw.cmd '-Dvaadin.skip=true' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dvaadin.skip=true' spring-boot:run
# In another terminal:
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

For isolated development startup, pass `-Dfmaiassistent.settings.file=...` to the application JVM (or `spring-boot.run.jvmArguments`) and use an isolated H2 URL through `spring-boot.run.arguments`. Stop the test instance afterwards so port 8080 remains free. The MCP endpoint follows the configured streamable HTTP endpoint; default is `http://127.0.0.1:8080/mcp`.

Repeat this manual procedure on each OS; record PASS/FAIL/NOT RUN separately:

1. With no snapshot loaded, enter `/tools` and press Enter. Confirm input clears, no chat message/provider request occurs, and the actual Tools dialog opens. Repeat with Commands selection, keyboard navigation and the Tools button. Escape closes suggestions/dialog as appropriate; prose mentioning `/tools` must not navigate.
2. Search a known tool and inspect nested schema/effect/requirements. Test all status filters, category enable/disable, reset and the four built-in presets. Save, edit and apply the named custom preset.
3. Select global scope and disable `fm27_find_players`. Connect an MCP inspector/client, initialize, list tools and call the cached/direct ID. Confirm it disappears from discovery and returns `isError=true`/`tool_disabled`; no handler executes. Re-enable and confirm discovery/execution are restored, with invalid arguments still rejected. Observe list-changed notification on an open event stream.
4. Run the deterministic real-server regression locally: `./mvnw -Dvaadin.skip=true -Dtest=McpProtocolCompatibilityTest test` (PowerShell: quote each `-D...` argument). This also exercises two protocol clients and a live list-changed event stream.
5. Open two OpenRouter conversations/tabs. Restrict a tool in just one conversation; global deny must still win in both. Use `OpenRouterConversationServiceTest` to inspect mocked outbound schemas and stale/forged returned calls without paid access. Test a non-tool model through the same local mock; ordinary chat should work with no tool definitions.
6. Trigger successful, failing and denied observed calls. Confirm status/duration and correctly scoped Activity. External MCP calls must stay external/global. Filter and clear history; verify other browser sessions' chats are excluded.
7. Set a local-effect tool to Ask and invoke it. Confirm a prompt-free, immediate `approval_required` error and no handler execution. Read-only tools must retain their normal Allow behavior.
8. Reopen Tools during streaming and change policy: running handlers may finish; subsequent calls must be denied. Close/reopen/detach/reconnect; disconnect in another tab and confirm an expired-dialog notice rather than an exception or another chat's settings.
9. Restart with the same isolated preferences. Global settings/custom preset persist; private chats/history do not. Try a corrupt copied policy and confirm the visible recovery notice and preserved recovery file after repair. Never corrupt the real user settings for a test.
10. From non-loopback access, verify global writes/custom saving are disabled/rejected; conversation restrictions remain session-local. Test keyboard labels and scrolling in a narrow desktop window, including Windows path/encoding behavior.
11. Where a human tester supplies an actual loaded FM save, repeat availability transitions: Needs data, Needs tactic, fixtures for employed managers, career-scoped evidence and exports. Do not modify the FM process/save. Preserve existing Codex, Antigravity and Copilot sign-in/model/send flows; live paid services are optional manual checks, not required for deterministic tests.

## Troubleshooting and release checklist

- `tool_disabled`: review global scope first, then the OpenRouter conversation selection. A preset cannot bypass a global deny.
- `approval_required`: Ask is safely denied in current integrations. Review the effect and choose Allow before a new invocation; no old call waits for approval.
- Needs data/tactic/club/career: follow the displayed remedy. Availability checks do not load data automatically.
- MCP still shows an old list: refresh/reconnect the client or open its notification stream. Execution remains checked independently.
- Settings cannot be saved: verify local file access; live policy remains unchanged on failure. A recovery notice does not grant extra access.
- CLI per-chat controls: unavailable by design without secure MCP correlation. Use global settings and retain the client's native permission UI.

Before any later release: run unit tests and `clean verify`, inspect architecture/coverage/frontend results, perform the manual procedure on both OSes, record unavailable paid/FM scenarios honestly, check privacy and actual handler effects, and confirm generated bundles/build artifacts are excluded from commits. This assignment does not publish, tag or push a release. Actual evidence is recorded in [the test report](tools-management-test-report.md).

Proposed convention for owner review (not applied to `AGENTS.md`): tools exposed to providers must use the shared checked callback registry; new metadata must classify demonstrated companion-app effects and never infer a browser conversation from uncorrelated MCP traffic.
