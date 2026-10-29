package com.github.fmaiassistent.domain;

import java.util.List;

/** Typed raw snapshot. Addresses embedded in diagnostic bytes are never durable relationship IDs. */
public record ContractSnapshot(String kind, int slot, long ownerUid, Long teamUid,
                               Integer agreedCode, Integer baseFee, Long totalFee, String feeKind,
                               Integer threshold, Integer played, Integer competitionIndex, Long competitionUid,
                               Integer conditionKind, Integer internalCompetition, Integer context1, Integer context2,
                               Long flagsA0, Long flagsA8, String coreHex, String auxiliaryHex, List<Term> terms,
                               String teamOwnerKind, Long teamOwnerUid) {
    public ContractSnapshot { terms = List.copyOf(terms); }
    /** Older snapshots did not normalize the TEAM ownership branch. */
    public ContractSnapshot(String kind, int slot, long ownerUid, Long teamUid, Integer agreedCode, Integer baseFee,
            Long totalFee, String feeKind, Integer threshold, Integer played, Integer competitionIndex, Long competitionUid,
            Integer conditionKind, Integer internalCompetition, Integer context1, Integer context2, Long flagsA0,
            Long flagsA8, String coreHex, String auxiliaryHex, List<Term> terms) {
        this(kind,slot,ownerUid,teamUid,agreedCode,baseFee,totalFee,feeKind,threshold,played,competitionIndex,competitionUid,
                conditionKind,internalCompetition,context1,context2,flagsA0,flagsA8,coreHex,auxiliaryHex,terms,null,null);
    }
    /** Raw bytes include opaque/padding bytes; semantic comparisons must use the decoded components. */
    public record Term(String group, int ordinal, String rawHex, Long valueRaw, Integer conditionRaw, Integer codeRaw,
                       String referenceKind, Long referenceUid, CompetitionReferences competitionReferences) {
        /** Older snapshots have only the independent AWARD relationship slot. */
        public Term(String group,int ordinal,String rawHex,Long valueRaw,Integer conditionRaw,Integer codeRaw,
                    String referenceKind,Long referenceUid) {
            this(group,ordinal,rawHex,valueRaw,conditionRaw,codeRaw,referenceKind,referenceUid,null);
        }
        /** Older retained snapshots have no normalized relationship. */
        public Term(String group, int ordinal, String rawHex, Long valueRaw, Integer conditionRaw, Integer codeRaw) {
            this(group, ordinal, rawHex, valueRaw, conditionRaw, codeRaw, null, null);
        }
    }

    /** Two different COMP relationships; stage UID keys remain preserved in the raw term. */
    public record CompetitionReferences(Long subjectUid, Long conditionUid) { }
}
