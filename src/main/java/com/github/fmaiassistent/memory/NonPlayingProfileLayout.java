package com.github.fmaiassistent.memory;

import java.util.HexFormat;
import java.util.List;

/** Native NON_PLAYING_DATA member, source-bound to26.3.2/2329565.
 * See nonplaying_named_native_field_model_checked_20261025.json: NPAT builds
 * records from C+10+index; their name handler uses the same index.
 * Values are raw signed bytes; scale describes native storage, not UI rounding.
 */
public record NonPlayingProfileLayout(int personToMemberRel, int constructionRel, int memberBytes,
        int profileOffset, int profileBytes, long memberVtableRva,
        long standalonePersonVtableRva, long combinedPersonVtableRva,
        int humanPersonToMemberRel, long humanPersonVtableRva, List<Field> fields) {
    public static final String MODULE_SHA256 = "eb6c86fab56e051fe41482c7b93d8cb0af716a1cc2c6197ce1babbff68372bb8";
    public NonPlayingProfileLayout { fields=List.copyOf(fields); }
    private static final NonPlayingProfileLayout CURRENT = createCurrent();
    public static NonPlayingProfileLayout current() { return CURRENT; }
    /** FM27 27.0.0+2486695 typed members; human PERSON moved sixteen bytes towards its owner. */
    public static NonPlayingProfileLayout fm27() {
        var l = current();
        return new NonPlayingProfileLayout(l.personToMemberRel(), l.constructionRel(), l.memberBytes(),
                l.profileOffset(), l.profileBytes(), 0x4CB2718L, 0x4D8BE78L, 0x4F75F38L,
                -0x438, 0x4CC08D8L, l.fields());
    }
    private static NonPlayingProfileLayout createCurrent() {
        return new NonPlayingProfileLayout(-0xF8,-4,64,8,52,0x449B2E8L,0x4565018L,0x4785848L,-0x448,0x44A5238L,List.of(
                new Field(0, "Attacking", false),
                new Field(1, "Business", false),
                new Field(2, "Coaching Technique", false),
                new Field(3, "Directness", false),
                new Field(4, "Authority", false),
                new Field(5, "Free Roles", false),
                new Field(6, "Interference", false),
                new Field(7, "Marking", false),
                new Field(8, "Offside", false),
                new Field(9, "Patience", false),
                new Field(10, "Trigger Press", false),
                new Field(11, "Resources", false),
                new Field(12, "Youngsters", false),
                new Field(13, "Determination", true),
                new Field(14, "Buying Players", false),
                new Field(15, "Mind Games", false),
                new Field(16, "Sitting Back", false),
                new Field(17, "User Of Play-Maker", false),
                new Field(18, "Use Of Subs", false),
                new Field(19, "Depth", false),
                new Field(20, "Fluidity", false),
                new Field(21, "Flexibility", false),
                new Field(22, "Hardness of training", false),
                new Field(23, "Squad rotation", false),
                new Field(24, "Tempo", false),
                new Field(25, "Width", false),
                new Field(26, "Coaching", true),
                new Field(27, "Coaching Goakeeping", true),
                new Field(28, "Judgement", true),
                new Field(29, "Judging Potential", true),
                new Field(30, "People Management", true),
                new Field(31, "Motivating", true),
                new Field(32, "Physiotherapy", true),
                new Field(33, "Tactics", true),
                new Field(34, "Coaching Attacking", true),
                new Field(35, "Coaching Defending", true),
                new Field(36, "Coaching Fitness", true),
                new Field(37, "Coaching Possession", true),
                new Field(38, "Coaching Technical", true),
                new Field(39, "Coaching Tactical", true),
                new Field(40, "Dirtiness allowance", true),
                new Field(41, "Coaching GK Handling", true),
                new Field(42, "Coaching GK Distribution", true),
                new Field(43, "Versatility", true),
                new Field(44, "Analysing Data", true),
                new Field(47, "Sports Science", true),
                new Field(48, "Eccentricity", true),
                new Field(49, "Negotiating", true),
                new Field(50, "Judging Staff Ability", true),
                new Field(51, "Coaching Set Pieces", true)));
    }
    public record Field(int index, String name, boolean scaledByFive) { }
    public record AttributeValue(String name, int nativeValue, int scale) { }

    /** Nullable older snapshots and unsupported source profiles remain unavailable. */
    public static List<AttributeValue> values(String profileHex,String sourceSha256) {
        if(!NativeModuleProfile.supported(sourceSha256)||profileHex==null||profileHex.length()!=104)return List.of();
        final byte[] raw;
        try { raw=HexFormat.of().parseHex(profileHex); } catch(IllegalArgumentException malformed) { return List.of(); }
        var layout=NativeModuleProfile.fm27(sourceSha256)?fm27():current();
        return layout.fields().stream().map(f->new AttributeValue(f.name(),raw[f.index()],f.scaledByFive()?100:20)).toList();
    }
}
