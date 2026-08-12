package com.github.fmaiassistent.tactic;

import java.nio.file.Path;

interface TacticImageTextExtractor {
    String extract(Path image, ImageKind kind);

    enum ImageKind {
        SHAPE,
        IN_POSSESSION,
        OUT_OF_POSSESSION,
        OTHER
    }
}
