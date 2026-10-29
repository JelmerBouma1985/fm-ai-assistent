package com.github.fmaiassistent.domain;

import java.time.LocalDate;

/** A PERSON+A0 record selected by offer index, not by position in the vector.
 * Presence alone does not prove an agreed future transfer: the offer's type,
 * membership and status must also be checked before publishing it as agreed.
 * Contract slot -1 is scoped to this employment record, never the active PERSON+A8 slot.
 * State is retained without inventing enum names; raw wages are not UI-rounded wages. */
public record FutureEmploymentSnapshot(int offerIndex, int state, String recordHex,
                                       int wageRaw, LocalDate endDate,
                                       boolean endDateUsesGameDateFlag, ContractSnapshot contract) { }
