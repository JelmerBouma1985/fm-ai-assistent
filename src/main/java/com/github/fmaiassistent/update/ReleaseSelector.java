package com.github.fmaiassistent.update;

import java.util.*;

public final class ReleaseSelector {
    private ReleaseSelector() { }
    public static Optional<ReleaseAsset> asset(UpdateRelease release, InstalledApplicationInfo installed) {
        if (installed.root() == null || !release.supportedGames().contains(installed.game())
                || SemVer.parse(release.version()).compareTo(SemVer.parse(installed.version())) <= 0) return Optional.empty();
        return release.assets().stream().filter(a -> a.distribution().equals("desktop") && a.os().equals(installed.os()) && a.arch().equals(installed.arch())).findFirst();
    }
    public static Optional<UpdateRelease> best(Collection<UpdateRelease> releases, InstalledApplicationInfo installed) {
        return releases.stream().filter(r -> asset(r, installed).isPresent()).max(Comparator.comparing(r -> SemVer.parse(r.version())));
    }
}
