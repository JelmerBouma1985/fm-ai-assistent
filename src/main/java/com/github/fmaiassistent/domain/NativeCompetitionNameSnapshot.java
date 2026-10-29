package com.github.fmaiassistent.domain;

/** A UID-checked COMP reference used by finances and contracts. Addresses/prefix are provenance, not lookup keys.
 * Null raw name means a null direct native pointer; zero-length text is preserved separately. */
public record NativeCompetitionNameSnapshot(long uniqueId,int recordIndex,long sourceAddress,String prefixHex,
        String nameHex,String shortNameHex,String name,String shortName) { }
