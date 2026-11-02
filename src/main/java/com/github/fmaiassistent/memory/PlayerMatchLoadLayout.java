package com.github.fmaiassistent.memory;

/** FM27 27.0.0+2486695 medical history and PLAYER_TEAM_FORM_CONCRETE<20>. */
public record PlayerMatchLoadLayout(long managerRva, int overrideDays, int overrideBegin, int overrideEnd,
        int virtualBasePointer, int personDisplacement, int personIndex, int medicalReference, int historyReference,
        int bucketVtableRva, int bucketCount, int bucketHead, int bucketItems, int bucketCapacity,
        int itemDetail, int itemDay, int itemYear, int detailMinutes, int detailRating, int goalkeeper) {
    public static PlayerMatchLoadLayout current() {
        return new PlayerMatchLoadLayout(0x56F5D08L, 0x31, 0x18, 0x20, 8, 4, 0x10,
                0xF8, 0x58, 0x4ED8A58, 0x10, 0xB8, 0x18, 20, 8, 0x10, 0x12, 0x15, 0x21, 0x150);
    }
}
