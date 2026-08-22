package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class FmSnapshotTools {
    private final SnapshotStatusService snapshots;

    public FmSnapshotTools(SnapshotStatusService snapshots) {
        this.snapshots = snapshots;
    }

    @Tool(name = "fm26_get_data_status", description = "Check which FM26 snapshot is loaded and whether FM has advanced since it was loaded. Call this before decisions when freshness matters.")
    public Map<String, Object> getDataStatus(
            @ToolParam(required = false, description = "Probe the running FM process and live game date. Defaults to true.") Boolean probeLive) {
        return snapshots.status(probeLive == null || probeLive);
    }

    @Tool(name = "fm26_refresh_data", description = "Refresh the application's local read-only snapshot from the running FM26 process. This never writes to Football Manager, but replaces the app's cached player, club and competition data.")
    public Map<String, Object> refreshData() throws IOException {
        return snapshots.refresh();
    }
}
