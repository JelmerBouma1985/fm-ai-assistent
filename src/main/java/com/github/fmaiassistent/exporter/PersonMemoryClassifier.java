package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.PersonIdentityLayout;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Classifies objects from FM's shared people collection using their RTTI metadata.
 *
 * <p>The dynamic offsets were runtime-checked against FM27 build
 * 27.0.0+2486695. Recheck them for every build; do not use ability values as a
 * substitute for person-type detection.</p>
 */
final class PersonMemoryClassifier {
    static final int STAFF_DYNAMIC_OFFSET = PersonIdentityLayout.currentSubtypes().staff();
    static final int PLAYER_DYNAMIC_OFFSET = PersonIdentityLayout.currentSubtypes().player();
    static final int PLAYER_STAFF_DYNAMIC_OFFSET = PersonIdentityLayout.currentSubtypes().playerStaff();
    static final int HUMAN_MANAGER_DYNAMIC_OFFSET = PersonIdentityLayout.currentSubtypes().humanManager();
    static final int PLAYER_STAFF_SHIFT = PLAYER_STAFF_DYNAMIC_OFFSET - PLAYER_DYNAMIC_OFFSET;

    private final ProcessMemoryReader reader;
    private final PersonIdentityLayout.Subtypes layout;
    private final Map<Long, Classification> classificationsByVtable = new HashMap<>();

    PersonMemoryClassifier(ProcessMemoryReader reader) {
        this(reader, PersonIdentityLayout.currentSubtypes());
    }

    PersonMemoryClassifier(ProcessMemoryReader reader, PersonIdentityLayout.Subtypes layout) {
        this.reader = reader;
        this.layout = layout;
    }

    Classification classify(long person) throws IOException {
        PlayerSnapshotValidator.validatePersonSubtypeLayout(layout);
        long vtable = reader.readU64(person);
        Classification cached = classificationsByVtable.get(vtable);
        if (cached != null) {
            return cached;
        }
        long metadata = reader.readU64(vtable - Long.BYTES);
        int dynamicOffset = reader.readI32(metadata + 4);
        Classification classification = new Classification(typeFor(dynamicOffset), dynamicOffset);
        classificationsByVtable.put(vtable, classification);
        return classification;
    }

    private PersonType typeFor(int dynamicOffset) {
        if (dynamicOffset == layout.staff()) return PersonType.STAFF;
        if (dynamicOffset == layout.player()) return PersonType.PLAYER;
        if (dynamicOffset == layout.playerStaff()) return PersonType.PLAYER_STAFF;
        if (dynamicOffset == layout.humanManager()) return PersonType.HUMAN_MANAGER;
        return PersonType.UNKNOWN;
    }

    enum PersonType {
        PLAYER,
        PLAYER_STAFF,
        STAFF,
        HUMAN_MANAGER,
        UNKNOWN;

        boolean hasPlayerData() {
            return this == PLAYER || this == PLAYER_STAFF;
        }

        boolean hasStandaloneStaffData() {
            return this == STAFF || this == HUMAN_MANAGER;
        }
    }

    record Classification(PersonType type, int dynamicOffset) {
    }
}
