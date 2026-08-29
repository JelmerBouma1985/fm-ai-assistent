package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Classifies objects from FM's shared people collection using their RTTI metadata.
 *
 * <p>The dynamic offsets are specific to the supported FM26 26.3.1 b1530 memory
 * layout. Keep them together here so a future game build cannot accidentally use
 * ability values as a substitute for person-type detection.</p>
 */
final class PersonMemoryClassifier {
    static final int STAFF_DYNAMIC_OFFSET = 0x100;
    static final int PLAYER_DYNAMIC_OFFSET = 0x288;
    static final int PLAYER_STAFF_DYNAMIC_OFFSET = 0x380;
    static final int HUMAN_MANAGER_DYNAMIC_OFFSET = 0x450;
    static final int PLAYER_STAFF_SHIFT = PLAYER_STAFF_DYNAMIC_OFFSET - PLAYER_DYNAMIC_OFFSET;

    private final ProcessMemoryReader reader;
    private final Map<Long, Classification> classificationsByVtable = new HashMap<>();

    PersonMemoryClassifier(ProcessMemoryReader reader) {
        this.reader = reader;
    }

    Classification classify(long person) throws IOException {
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

    private static PersonType typeFor(int dynamicOffset) {
        return switch (dynamicOffset) {
            case STAFF_DYNAMIC_OFFSET -> PersonType.STAFF;
            case PLAYER_DYNAMIC_OFFSET -> PersonType.PLAYER;
            case PLAYER_STAFF_DYNAMIC_OFFSET -> PersonType.PLAYER_STAFF;
            case HUMAN_MANAGER_DYNAMIC_OFFSET -> PersonType.HUMAN_MANAGER;
            default -> PersonType.UNKNOWN;
        };
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
