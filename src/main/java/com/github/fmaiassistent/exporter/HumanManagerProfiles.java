package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.StaffRecordLayout;

import java.io.IOException;

/** Separates FM's account-only HUMAN_NON_PLAYER placeholder from career managers. */
public final class HumanManagerProfiles {
    private HumanManagerProfiles() { }

    /** Require all observed placeholder markers; unemployment or a parenthesized name alone is insufficient. */
    public static boolean isAccountProfile(ProcessMemoryReader reader, long person, String name,
                                           StaffRecordLayout layout) throws IOException {
        String displayName = name == null ? "" : name.strip();
        return displayName.length() > 2 && displayName.startsWith("(") && displayName.endsWith(")")
                && reader.readU16(person + layout.dateOfBirthRel()) == 1
                && reader.readU16(person + layout.dateOfBirthRel() + Short.BYTES) == 1900
                && reader.readU64(person + layout.contractRefRel()) == 0
                && reader.readU64(person + ContractRecordLayout.current().links().additional()) == 0;
    }
}
