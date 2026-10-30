package com.github.fmaiassistent.memory;

/** Exact-image AWARD prefix/name fields; independently captured checkpoint54, not a full allocation layout. */
public record AwardNameLayout(int registryRva,int registryHeaderRel,long vtableRva,int prefixBytes,
        int indexRel,int uidRel,int nameRel,int shortNameRel,int lengthRel,int dataRel,int maximumBytes,
        int rowBytes,int amountRel,int stateRel) {

    public static AwardNameLayout current(){return new AwardNameLayout(0x57036D0,0x80,0x4CF6078L,64,8,12,0x30,0x38,0,4,1024,16,8,12);}
    public int identityBytes(){return uidRel+Integer.BYTES;}
    public boolean identityMatches(long uid,int index,String prefixHex){
        if(uid<=0||uid>0xffffffffL||index<0||index>=100000||prefixHex==null||prefixHex.length()!=identityBytes()*2)return false;
        try {var b=java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(prefixHex)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            return b.getInt(indexRel)==index&&Integer.toUnsignedLong(b.getInt(uidRel))==uid;
        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return false;}
    }
    /** Unknown nonzero native state is retained but not assigned an invented display meaning. */
    public java.util.OptionalInt bonusAmount(String rawHex){
        if(rawHex==null||rawHex.length()!=rowBytes*2)return java.util.OptionalInt.empty();
        try {byte[] raw=java.util.HexFormat.of().parseHex(rawHex);
            int amount=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(amountRel);
            return raw[stateRel]!=0||amount<0?java.util.OptionalInt.empty():java.util.OptionalInt.of(amount);
        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return java.util.OptionalInt.empty();}
    }
    /** Native name strings use the same bounded length/text representation as captured COMP strings. */
    public CompetitionNameLayout.Names decode(String nameHex,String shortNameHex){
        return new CompetitionNameLayout(nameRel,shortNameRel,lengthRel,dataRel,4,maximumBytes).decode(nameHex,shortNameHex);
    }
}
