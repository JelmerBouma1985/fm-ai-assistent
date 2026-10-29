package com.github.fmaiassistent.memory;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Native F999E0 cnam/csnm leaves and F95220 length/text access, checkpoint45. */
public record CompetitionNameLayout(int nameRel,int shortNameRel,int lengthRel,int dataRel,int lengthBytes,int maximumBytes) {
    public static CompetitionNameLayout current(){return new CompetitionNameLayout(0x40,0x48,0,4,4,1024);}
    public int maximumHexCharacters(){return (dataRel+maximumBytes)*2;}
    public String decode(String hex) {
        if(hex==null)return null;
        if(hex.length()<dataRel*2||hex.length()>maximumHexCharacters()||hex.length()%2!=0)
            throw new IllegalArgumentException("Competition name extent anchor failed");
        byte[] raw=HexFormat.of().parseHex(hex);int length=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(lengthRel);
        if(length<0||length>maximumBytes||raw.length!=dataRel+length)throw new IllegalArgumentException("Competition name length anchor failed");
        try {
            String name=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw,dataRel,length)).toString();
            if(name.indexOf('\0')>=0)throw new IllegalArgumentException("Competition name contains embedded NUL");
            return name;
        }catch(CharacterCodingException invalid){throw new IllegalArgumentException("Invalid competition name UTF-8",invalid);}
    }
    public Names decode(String nameHex,String shortNameHex){return new Names(decode(nameHex),decode(shortNameHex));}
    public record Names(String name,String shortName) {
        public Optional<String> display(){return name!=null&&!name.isBlank()?Optional.of(name)
                :shortName!=null&&!shortName.isBlank()?Optional.of(shortName):Optional.empty();}
    }
}
