package com.github.fmaiassistent.update;

public record ReleaseAsset(String distribution, String os, String arch, String file, String sha256, long sizeBytes) { }
