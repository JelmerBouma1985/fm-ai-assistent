# FM AI Assistent

FM AI Assistent is an AI assistant companion for Football Manager 26.

It reads FM26 data from RAM and makes the data available to AI assistants through MCP. An AI assistant can use this information to help with buying and selling players, finding profitable young talents, comparing squads, checking club finances, and giving tactical advice based on the players in your save.

The app also includes a frontend where you can search and filter the data yourself. You can inspect players, clubs, competitions, attributes, positions, reputations, contracts, salaries, asking prices, and budgets.

## How To Install

### Option 1: Native Image

Download the native image.

Make it executable:

```bash
chmod +x ./fmaiassistent
```

Start it from a terminal:

```bash
./fmaiassistent
```

### Option 2: Java Jar

Minimum requirement: Java 25.

Download the jar and run:

```bash
java -jar ./fmaiassistent.jar
```

The application starts on:

```text
http://127.0.0.1:8080
```

## Use AI Assistent

Keep FM26 running with your save loaded. Start FM AI Assistent before using the MCP tools.

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
