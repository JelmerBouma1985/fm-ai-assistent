package com.github.fmaiassistent.update;

import java.util.List;
import java.util.Set;

public record UpdateRelease(String version, Set<String> supportedGames, String releaseNotes, List<ReleaseAsset> assets) { }
