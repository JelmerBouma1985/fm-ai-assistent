package com.github.fmaiassistent.domain;

/** Referenced native AWARD identity and captured strings; pointers in prefixHex are diagnostic only. */
public record NativeAwardNameSnapshot(long uniqueId,int recordIndex,String prefixHex,
        String nameHex,String shortNameHex,String name,String shortName) { }
