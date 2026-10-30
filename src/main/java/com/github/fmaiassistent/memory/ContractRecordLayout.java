package com.github.fmaiassistent.memory;

import java.util.List;

/** Native FM27 contract layouts for the supported executable image. */
public record ContractRecordLayout(Links links, Full full, Loan loan, List<Vector> sharedVectors,
                                   TermFields fullTerm, TermFields sharedTerm, Plain bClub, Plain basic, Award award, TeamOwner teamOwner) {
    public ContractRecordLayout { sharedVectors = List.copyOf(sharedVectors); }
    public record Links(int primary, int additional, int index, int uid, int owner, int team,
                        int agreed, int registryHeader, int teamsRegistry, int competitionsRegistry,
                        int vectorEnd, int vectorCapacity, int additionalSlots) { }
    public record Full(int size, long vtable, int clauses, int competitionBonuses, int awards,
                       int clauseStride, int competitionStride, int awardStride) { }
    public record Loan(int size, long vtable, int shared, int flagsA0, int flagsA8,
                       int baseFee, int competition, int threshold, int played,
                       int internalCompetition, int context1, int context2, int conditionKind, int sharedSize,
                       int competitionBonuses, int awards, int competitionStride, int awardStride) { }
    public record Vector(String name, int offset, int stride) { }
    public record TermFields(int value, int condition, int code) { }
    public record Plain(int size, long vtable, String kind) { }
    public record Award(int registry, int reference, long vtable) { }
    public record TeamOwner(int flag, int reference, int clubsRegistry, int nationalContainersRegistry,
                            long clubVtable, long nationalContainerVtable) { }

    /** FM27 27.0.0+2486695; typed live census in fm27/contracts_inventory.json. */
    public static ContractRecordLayout current() {
        return new ContractRecordLayout(
                new Links(0xA8, 0xB0, 8, 12, 8, 0x10, 0x54, 0x80,
                        0x5703768, 0x57036E8, 8, 16, 4),
                new Full(0xC8, 0x4B00AE8, 0x68, 0x80, 0x98, 8, 32, 16),
                new Loan(0xF8, 0x4DC91F8, 0x68, 0xA0, 0xA8, 0xB0, 0xB4,
                        0xEF, 0xF0, 0x90, 0x94, 0x98, 0x9C, 200, 0x70, 0x88, 32, 16),
                List.of(new Vector("SHARED_00", 0, 12), new Vector("SHARED_18", 0x18, 8),
                        new Vector("SHARED_30", 0x30, 8), new Vector("SHARED_48", 0x48, 8),
                        new Vector("SHARED_60", 0x60, 8), new Vector("SHARED_78", 0x78, 16),
                        new Vector("SHARED_90", 0x90, 32), new Vector("SHARED_A8", 0xA8, 16)),
                new TermFields(0, 4, 6), new TermFields(0, 4, 8),
                new Plain(0x68, 0x4F53F28, "B_CLUB"), new Plain(0x28, 0x4B01058, "BASIC"),
                new Award(0x57036D0, 0, 0x4CF6078),
                new TeamOwner(0x2F, 0x30, 0x57036E0, 0x5703730, 0x4CDA1F8, 0x4CEC0B8));
    }
}
