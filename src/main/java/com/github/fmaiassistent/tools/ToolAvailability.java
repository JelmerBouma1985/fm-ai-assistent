package com.github.fmaiassistent.tools;

import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import org.springframework.stereotype.Service;

/** Cheap, side-effect-free checks. Never probes the game, executes a tool or contacts a provider. */
@Service
public class ToolAvailability {
    private final LoadMetadataRepository metadata;
    private final TacticContextService tactics;
    private final ManagedClubContextService clubs;
    public ToolAvailability(LoadMetadataRepository metadata, TacticContextService tactics, ManagedClubContextService clubs) {
        this.metadata = metadata;
        this.tactics = tactics;
        this.clubs = clubs;
    }
    public String unavailable(ToolRegistry.Descriptor tool) {
        if (tool.snapshot() && !metadata.existsById("snapshot_id")) return "Needs game data: load FM27 data first.";
        if (tool.tactic() && (!tactics.current().active() || tactics.current().definition() == null))
            return "Needs tactic: load a tactic in Context.";
        if (tool.managedClub() && !clubs.current().available())
            return "Needs managed club: select an employed club manager in Context.";
        if (tool.career() && (clubs.currentCareerKey() == null || clubs.currentCareerKey().isBlank()))
            return "Needs career identity: refresh FM27 data before recording evidence.";
        return "";
    }
}
