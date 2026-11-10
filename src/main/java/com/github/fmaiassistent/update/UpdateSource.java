package com.github.fmaiassistent.update;

import java.io.*;
import java.util.Optional;

public interface UpdateSource {
    Optional<UpdateRelease> findBestCompatibleRelease(InstalledApplicationInfo installed) throws IOException;
    InputStream openAsset(ReleaseAsset asset) throws IOException;
}
