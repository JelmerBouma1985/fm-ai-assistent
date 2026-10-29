package com.github.fmaiassistent.domain;

/** A persisted, identity-checked contract snapshot; native pointers remain diagnostic bytes only. */
public record StoredContract(long id, String moduleSha256, ContractSnapshot snapshot, FutureTransferSnapshot futureTransfer,String clubName,
                             java.util.Map<Long,String> awardNames,java.util.Map<Long,String> competitionNames) {
    public StoredContract {awardNames=java.util.Map.copyOf(awardNames);competitionNames=java.util.Map.copyOf(competitionNames);}
    public StoredContract(long id,String moduleSha256,ContractSnapshot snapshot,FutureTransferSnapshot futureTransfer,String clubName,java.util.Map<Long,String> awardNames){this(id,moduleSha256,snapshot,futureTransfer,clubName,awardNames,java.util.Map.of());}
    public StoredContract(long id,String moduleSha256,ContractSnapshot snapshot,FutureTransferSnapshot futureTransfer,String clubName){this(id,moduleSha256,snapshot,futureTransfer,clubName,java.util.Map.of());}
    public StoredContract(long id,String moduleSha256,ContractSnapshot snapshot){this(id,moduleSha256,snapshot,null,null);}
    public StoredContract(long id,String moduleSha256,ContractSnapshot snapshot,FutureTransferSnapshot futureTransfer){this(id,moduleSha256,snapshot,futureTransfer,null);}
}
