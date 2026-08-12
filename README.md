# FM AI Assistent for Linux and Windows

FM AI Assistent is an AI assistant companion for Football Manager 26 running on Linux or Windows 11.

It reads FM26 data from RAM and makes the data available to AI assistants through MCP. An AI assistant can use this information to help with buying and selling players, finding profitable young talents, comparing squads, checking club finances, and giving tactical advice based on the players in your save.

The app also includes a frontend where you can search and filter the data yourself. You can inspect players, clubs, competitions, attributes, positions, reputations, contracts, salaries, asking prices, and budgets.

## How To Install

### Option 1: Native Image

Download the native image.

Make it executable:

```bash
chmod +x ./fm-ai-assistent
```

Start it from a terminal:

```bash
./fm-ai-assistent
```

### Option 2: Java Jar

Minimum requirement: Java 25.

Download the jar and run:

```bash
java -jar ./fm-ai-assistent.jar
```

On Windows, run the command from PowerShell or Command Prompt:

```powershell
java -jar .\fm-ai-assistent.jar
```

Start FM26 and load a save before loading RAM data. Run FM AI Assistent as the same Windows user as FM26. Administrator privileges are normally not required, but may be needed if FM26 itself was started as administrator.

The application starts on:

```text
http://127.0.0.1:8080
```

## Use AI Assistent

Keep FM26 running with your save loaded. Start FM AI Assistent and Load data (not necessary but be wise with your tokens) before using the MCP tools.

### FM26 tactic context

The **AI assistent** tab has a collapsible **Tactic context** section. You can:

- enter the location of a `.fmf` tactic file;
- upload a `.fmf` tactic file; or
- optionally select a folder containing an FMF plus extra screenshots/readable exports.

The application reads the FM26 archive catalog, decrypts and decompresses the embedded tactic resource, and converts the tactic name, tactical style, mentality, in-possession roles/duties, and out-of-possession roles/duties into AI-readable text. The `.fmf` file is sufficient: screenshots and Football Manager Resource Archiver are not required. The resulting context is sent to the selected Codex, Antigravity, or GitHub Copilot conversation. The file is processed locally and is not uploaded to a separate conversion service.

### Embedded Codex chat

The **AI assistent** tab runs the locally installed `codex app-server`. It uses the Codex ChatGPT login and existing MCP configuration; it does not use or store an API key. On first use, select **Sign in with ChatGPT** in the tab and complete the browser flow.

The terminal login command remains an optional alternative. The MCP configuration is still managed by Codex:

```bash
codex login
codex mcp add fm-genie26 --url http://127.0.0.1:8080/mcp
```

Start FM AI Assistent normally, open `http://127.0.0.1:8080`, load the current FM26 save from RAM, and select **AI assistent**. Codex conversations are persisted by Codex and can be resumed from the conversation sidebar.

The executable and workspace can be overridden without credentials:

```properties
app.codex.executable=codex
app.codex.working-directory=.
```

Run `codex login status` and `codex mcp list` if the chat reports an authentication or MCP connectivity problem.

### Embedded Antigravity chat

Select **Antigravity** in the **AI assistent** tab. It runs the locally installed `agy` CLI in headless `stream-json` mode. It reuses Antigravity's Google login, MCP configuration, agent configuration, skills, and global permissions. FM AI Assistent does not read Google credentials or require a Gemini API key.

First authenticate Antigravity normally from a terminal:

```bash
agy
```

Complete Google sign-in and approve the workspace if Antigravity asks. Exit Antigravity after authentication.

Register the FM AI Assistent MCP server in Antigravity's user-level MCP configuration file:

```text
~/.gemini/config/mcp_config.json
```

The complete configuration is:

```json
{
  "mcpServers": {
    "fm-ai-assistent": {
      "serverUrl": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

Antigravity headless mode cannot open an interactive approval prompt. Add explicit global permission grants for the application's six read-only MCP tools in:

```text
~/.gemini/antigravity-cli/settings.json
```

Merge the following entries into the existing `permissions.allow` array rather than replacing existing settings:

```json
{
  "permissions": {
    "allow": [
      "mcp(fm-ai-assistent/fm26_find_clubs)",
      "mcp(fm-ai-assistent/fm26_find_players)",
      "mcp(fm-ai-assistent/fm26_get_club_context)",
      "mcp(fm-ai-assistent/fm26_get_player_details)",
      "mcp(fm-ai-assistent/fm26_get_role_attributes)",
      "mcp(fm-ai-assistent/fm26_transfer_shortlist)"
    ]
  }
}
```

Exact per-tool grants are used instead of `--dangerously-skip-permissions`. They are global Antigravity permissions and therefore apply whenever this MCP server is configured under the name `fm-ai-assistent`.

Start FM AI Assistent before verifying the connection, then inspect the effective global grants without spending model quota:

```bash
agy -p "/permissions" --output-format json
```

For an end-to-end check, open the **AI assistent** tab, select **Antigravity**, and ask:

```text
What agents or FM26 tools are available in my application?
```

If Antigravity reports that the MCP server is unavailable, confirm that FM AI Assistent is running at `http://127.0.0.1:8080` and that the server name in `mcp_config.json` exactly matches the `fm-ai-assistent` name used by the permission rules.

### Embedded GitHub Copilot chat

Select **GitHub Copilot** in the **AI assistent** tab. The application uses official GitHub Copilot Java SDK `1.0.9`, which owns one persistent local Copilot CLI process. Login credentials, instructions, skills, custom agents, session history, and MCP configuration remain owned by Copilot. FM AI Assistent does not read GitHub credentials or require a model API key.

Install GitHub Copilot CLI and authenticate once:

```bash
copilot --version
copilot login
```

Add FM AI Assistent's local HTTP MCP server to Copilot's normal user configuration:

```bash
copilot mcp add --transport http fm-ai-assistent http://127.0.0.1:8080/mcp
copilot mcp list
```

If `copilot mcp list` already shows `fm-ai-assistent`, do not add it again. Start FM AI Assistent before testing MCP calls. Copilot permission requests appear in Vaadin with **Allow once** and **Deny** actions. Requests from the application's own `fm-ai-assistent` MCP server also offer **Always allow this MCP tool**. This persists an exact tool rule for the current workspace through Copilot's location-scoped permission API; it does not approve other MCP tools, shell commands, or file writes. The integration never uses `--allow-all` or `--yolo`.

Optional runtime settings:

```properties
app.ai.copilot.enabled=true
app.ai.copilot.executable=copilot
app.ai.copilot.working-directory=.
app.ai.copilot.startup-timeout=30s
app.ai.copilot.permission-timeout=5m
# app.ai.copilot.model=
# app.ai.copilot.reasoning-effort=
```

For an end-to-end check, load FM26 data, select **GitHub Copilot**, then ask:

```text
What agents or FM26 tools are available in my application?
```

The web server binds to `127.0.0.1` by default. Keep this local: Copilot can run tools, access workspace files, and call the application MCP server.

### Codex

Add the MCP server in a terminal:

```bash
codex mcp add fmaiassistent --url http://127.0.0.1:8080/mcp
```

Restart Codex or start a new Codex session after adding the MCP.

### Claude

Add it to Claude Desktop config as an HTTP MCP server.

Add:

```json
{
  "mcpServers": {
    "fmaiassistent": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

Restart Claude Desktop after changing the config.

## Use of the
- Start Football Manager 26 and load your game.
- Start the fm-ai-assistent
- navigate to localhost:8080
- Load from RAM
- use AI to analyze

## AI Examples

### prompt
```
i am playing as Feyenoord, i am looking for a new DL that can play in possession as a inverted full back and out of possession as a holding full back. I want the new player to become a immediatly starter or a young player
  that becomes better then my current DL. use the fmaiassistent mcp.
```

```
Best pick depends on money.

  1. Ramon Hendriks, Stuttgart
  Best answer if you can stretch above budget.
  CA 135 / PA 145, age 23, £11.85m, £13.3k/w. D(LC), DL 19, DC 20, DM 13.
  Starter now over Smal/Bos, and higher ceiling than Bos. Holding FB fit good: marking 14, tackling 14, positioning 13, strength 16, pace 16, work rate 17. Inverted possession okay, not elite: passing 11, first touch 11,
  technique 11.

  2. Jayden Oosterwolde, Fenerbahce
  Best immediate starter inside current budget.
  CA 135 / PA 140, age 24, £8.2m, £35.4k/w. D(LC), DL 16, DC 20.
  Strong holding FB: tackling 15, marking 14, strength 15, pace 16, teamwork 16, bravery 18. But inverted possession weaker: first touch 8, technique 11, vision 9. Also temperament 3 = discipline/personality risk.

  3. Frans Kratzig, Salzburg
  Best tactical fit for inverted full back, but not immediate upgrade.
  CA 126 / PA 140, age 22, £4.75m, £16.6k/w. D/WB(L), DM, M(C), DL 20, WBL 18, DM 15, MC 15.
  Possession fit strong: technique 15, first touch 14, passing 13, teamwork 16, versatility 19. Weak as holding FB: marking 9, positioning 10, strength 8. Ceiling equals Bos, not clearly better.

  4. Jesus Angulo, Tigres
  Solid immediate defensive option.
  CA 133 / PA 138, age 27, £6.85m, £14.4k/w. D(LC), WB(L), DL 20, DC 20.
  Good holding FB: positioning 15, tackling 14, marking 13, concentration 14, composure 14. Possession okay only: passing 12, technique 12. No DM/MC fit.

  Avoid for this role
  Jan-Niklas Beste: CA 135 and affordable at £8.55m, but more winger/playmaker than holding full back. Marking 9, positioning 9, strength 9, no DM.
  John Tolkin: cheap, nice squad option, but CA 123. Not starter over Smal/Bos.

  My call: Ramon Hendriks if you can raise ~£3m extra. If budget fixed, buy Oosterwolde for immediate XI or Kratzig if you value role fit and development over current CA.
```

### prompt

```
this is my tactic. What would your first 11 be using this tactic?  which positions do you think we should definitly get an upgrade? And what roles or positions should i change in my tactic to better suite my players?
  Player,in possesion role,out of possesion role
  GK,Ball playing GK,Sweeper keeper
  DL,Inverted Full Back, Holding full back
  DC,Centreback,CentreBack
  DC,Ball playing Centreback, Centreback
  DR,Inverted Wing Back, Pressing Full Back
  DMC: Deep lying playmaker, Defensive Midfielder
  MC:Midfield Playmaker,Central Midfielder
  MC:Advanded Playmaker,Pressing Central Midfielder
  AML:Wide Forward,Winger
  AMR:Winger,Winger
  ST:Deep Lying Forward,Tracking Centre Forward
```

### response
```
First XI

  GK: Wellenreuther
  Good SK/BPGK fit. Kicking 15, throwing 15, rushing out 14, one-on-ones 15.

  DL: Smal for now
  Best current DL. Passing 14, concentration 15, work rate 15. Bos has more PA but lower now.

  DC: Watanabe
  Best pure CB. CA 136, marking/tackling/positioning 14, teamwork 15.

  DC: Ahmedhodzic as BPD
  Passing 13, vision 13, composure 13. Not elite BPD, but best fit next to Watanabe.

  DR: Read
  Must start. CA 136/PA 152. Great IWB/pressing FB hybrid: passing 14, first touch 14, technique 14, pace 15, DR 20, DM 13.

  DMC: Moder
  Better than Hwang for DLP/DM balance. DM 19, MC 20, passing 14, tackling 13, strength 13.

  MC: Hwang
  Best player. Use as main playmaker, not DMC stopper. Passing 16, technique 16, decisions 16, teamwork 18.

  MC: Sem Steijn
  Best advanced CM. CA 139. Off ball 17, decisions 15, first touch/technique/vision 15. Presses well: work rate 17.

  AML: Sterling
  Best wide forward. Dribbling 15, off ball 17, acceleration 15, AM(L) 20.

  AMR: Hadj Moussa
  Best natural right winger. Left-footed, AMR 20, dribbling 17, crossing 14. Risk: teamwork 9, determination 5, consistency 9.

  ST: Ueda
  Best striker. Finishing 15, composure 15, anticipation 15, work rate 17.

  Definite Upgrades

  1. DL
     Biggest hole. Smal 131, Bos 129. For your IFB/HFB role, buy Ramon Hendriks if possible. If budget fixed, Oosterwolde or Kratzig depending on need.

  2. AMR
     Hadj Moussa has talent but bad mental profile for possession/pressing. Need more reliable winger: teamwork, decisions, work rate, consistency.

  3. BPD
     Ahmedhodzic okay, not top. Need CB with passing 14+, composure 14+, decisions 14+, plus normal CB defending.

  Tactic Changes

  Change ST in possession: Deep Lying Forward -> Advanced Forward or Pressing Forward.
  Ueda is scorer/runner, not creator. Passing 10, first touch 12. His best stuff is finishing, off ball, work rate, aggression.

  Change DMC: Deep Lying Playmaker -> Defensive Midfielder / Half Back if Hwang plays MC.
  Moder can pass enough, but team already has Hwang + Steijn. You need rest defence because both fullbacks invert/step in.

  Change AMR out of possession: Winger -> Pressing Winger only if Hadj Moussa accepts it.
  He has pace/dribble, but weak teamwork/determination. If he underperforms, use Valente AMR for more team structure.

  Change left side: keep Sterling Wide Forward, but DL should be more conservative.
  Smal/Hendriks as holding/inverting support behind Sterling. Do not make DL too aggressive.

  Best shape with current squad: asymmetric possession. Read inverts from right, Smal holds left, Hwang controls MC, Steijn attacks, Ueda runs channels.
```
