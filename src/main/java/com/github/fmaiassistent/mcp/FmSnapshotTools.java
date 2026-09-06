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

    @Tool(name = "fm26_get_data_status", description = "Check which FM26 snapshot is loaded and whether FM has advanced since it was loaded. Use the loaded snapshot when stale is false or unknown; do not reload just because the user asks a new question or already loaded data. The probe cannot detect same-day changes.")
    public Map<String, Object> getDataStatus(
            @ToolParam(required = false, description = "Probe the running FM process and live game date. Defaults to true.") Boolean probeLive) {
        return snapshots.status(probeLive == null || probeLive);
    }

    @Tool(name = "fm26_refresh_data", description = "Load missing or stale FM26 data. By default reuses a loaded snapshot if the date/process probe finds no change, including when freshness cannot be verified. Do not call after the user already loaded data. Use force=true only for an explicit refresh request or known game changes since loading, including same-day changes or switching saves. Never writes to Football Manager.")
    public Map<String, Object> refreshData(
            @ToolParam(required = false, description = "Force a full RAM reload for an explicit refresh request or known changes since the last load. Defaults to false.") Boolean force) throws IOException {
        return snapshots.refresh(Boolean.TRUE.equals(force));
    }
}
