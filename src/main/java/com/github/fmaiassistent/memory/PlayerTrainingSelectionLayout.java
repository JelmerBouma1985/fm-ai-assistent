package com.github.fmaiassistent.memory;

/** FM27 27.0.0+2486695 training-role selection, native15F3FD0/15EAF40/46F1700. */
public record PlayerTrainingSelectionLayout(long positionRolesRva, long attributesBeginRva, long attributesEndRva,
        int positions, int attributeBase, int firstAttribute, int currentAbility, int cachedAbility,
        java.util.List<CodeAnchor> codeAnchors) {
    public PlayerTrainingSelectionLayout { codeAnchors = java.util.List.copyOf(codeAnchors); }
    public record CodeAnchor(int rva, int bytes, String sha256) { }
    public static int positionAttributeIndex(int position) {
        return switch (position) {
            case 0 -> 0; case 1 -> 1; case 2 -> 4; case 3 -> 2; case 4 -> 3;
            case 5 -> 14; case 6 -> 13; case 7 -> 5; case 8 -> 8; case 9 -> 6;
            case 10 -> 7; case 11 -> 11; case 12 -> 9; case 13 -> 10; case 14 -> 12;
            default -> throw new IllegalArgumentException("Invalid native position");
        };
    }
    public static PlayerTrainingSelectionLayout current() {
        return new PlayerTrainingSelectionLayout(0x5708BB8L, 0x5708A48L, 0x5708A50L,
                0x150, 0x148, 0x17, 0x264, 0x273, java.util.List.of(
                new CodeAnchor(0x46F1A80,944,"5df4f0524e094e3c598b1de4f4fff16dc46149e3f58edb6b4f985990a8803c46"),
                new CodeAnchor(0x3C53E10,3168,"6f04b6cb2a2c180abed35fcef52ded6bdb5a1d4f97f52c2f4afd540d9e06b917"),
                new CodeAnchor(0x15EAF40,704,"1ebcdf9cf2fa5635e4205f9cf2d8f6261385c37e4ca8ba042aefa0b9375cb05d"),
                new CodeAnchor(0x46F1700,896,"937eac7991b00e6b38b6c96090a14c9b74628261a969b8a9d5ef858fc54bbf69"),
                new CodeAnchor(0x15F3FD0,1104,"e6861add8e86df8d281ee9c7f84456bf84b387eeac04cad8f1d176d4b7b63877"),
                new CodeAnchor(0x4FBC68C,99,"dcd419fd828e310ce7893f6847651a9303d2d99a4d81a0c9d00a92f9f02c0319")));
    }
}
