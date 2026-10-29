package com.github.fmaiassistent.domain;

import java.time.LocalDate;

/** Status19 offer selected by native PERSON membership; separate from current contract slots. */
public record FutureTransferSnapshot(LocalDate transferDate, FutureEmploymentSnapshot employment) { }
