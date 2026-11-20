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

    public static PlayerTrainingSelectionLayout forModule(String profile) {
        if (NativeModuleProfile.FM27_SHA256.equals(profile)) return current();
        if (!NativeModuleProfile.STEAM_26620374_SHA256.equals(profile))
            throw new IllegalArgumentException("Training role selection is not verified for this FM27 image");
        return new PlayerTrainingSelectionLayout(NativeModuleProfile.fitnessRva(profile, 0x5708BB8),
                NativeModuleProfile.fitnessRva(profile, 0x5708A48), NativeModuleProfile.fitnessRva(profile, 0x5708A50),
                0x150, 0x148, 0x17, 0x264, 0x273, java.util.List.of(
                new CodeAnchor(0x4978F60, 726, "37d1dec1c0d08a4348ddabbbe3861c4c37d33222c61386a06e534cb259487fdc"),
                new CodeAnchor(0x4978A70, 1258, "59a75a68d255347f2338009683bd3a1be58ca09cfe9f2c9dec60462e52c51067"),
                new CodeAnchor(0x3E6E400, 2465, "354b95c07043423964b8c0f3581e60b1f62ce608e535103d83727e9e0c207ee8"),
                new CodeAnchor(0x166A060, 694, "bac20aa5d4e3568a899bdd6b2df4e823a96e7b4fee90a005e4c6b1a98c94af46"),
                new CodeAnchor(0x16730D0, 1097, "7acfa659f2e63956f152d953e54cbd8beaa4bf7375d80b4c23c1a5e63775c855")));
    }
}
