package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerInjuryRiskLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Objects;

/** Passive translations of FM's medical calculations; never invokes target code. */
public final class PlayerInjuryRiskReader {
    private final PlayerInjuryRiskLayout layout;
    private final PlayerMedicalAssessmentReader medicalAssessment = new PlayerMedicalAssessmentReader();

    public PlayerInjuryRiskReader() {
        this(PlayerInjuryRiskLayout.current());
    }

    public PlayerInjuryRiskReader(PlayerInjuryRiskLayout layout) {
        this.layout = Objects.requireNonNull(layout);
    }

    /** Inputs to native 2DB26F0. An absent injury type is represented by zero. */
    public SusceptibilityInputs readSusceptibilityInputs(ProcessMemoryReader reader, long player,
            int condition, int fatigue, long injuryType) throws IOException {
        PlayerSnapshotValidator.validatePlayerInjuryRiskLayout(layout);
        int rawProneness = reader.readU8(player + layout.injuryPronenessRel());
        int encoding = reader.readU8(player + layout.attributeEncodingRel());
        if (rawProneness > 100 || (encoding & 0x80) == 0 && rawProneness > 20
                || condition < 0 || condition > 10000
                || fatigue < -1000 || fatigue > 1000) {
            throw new IOException("Player injury-risk range anchor failed");
        }
        int[] recurring = new int[3];
        for (int i = 0; i < recurring.length; i++) {
            recurring[i] = reader.readI16(player + layout.recurringInjuriesRel()
                    + (long) i * layout.recurringInjuryStride());
        }
        Integer typeIndex = null;
        int typeKind = 0;
        int typeOccurrence = 0;
        if (injuryType != 0) {
            typeIndex = reader.readI32(injuryType + layout.injuryTypeIndexRel());
            typeKind = reader.readU8(injuryType + layout.injuryTypeKindRel());
            typeOccurrence = reader.readU8(injuryType + layout.injuryTypeOccurrenceRel());
            if (typeIndex < 0 || typeIndex > 32767) {
                throw new IOException("Player injury-type index anchor failed");
            }
        }
        return new SusceptibilityInputs(rawProneness, (encoding & 0x80) != 0,
                condition, fatigue, typeIndex, typeKind, typeOccurrence,
                recurring[0], recurring[1], recurring[2]);
    }

    /** Exact scalar arithmetic of 2DB26F0, including its native float truncation. */
    public static int baseSusceptibility(SusceptibilityInputs input) {
        if (input.injuryTypeIndex() != null && input.injuryTypeKind() == 0x30) {
            return 50;
        }
        int score;
        if (input.injuryTypeIndex() != null
                && (input.injuryTypeOccurrence() == 1 || input.injuryTypeOccurrence() == 2)) {
            score = 50;
        } else {
            int prone = input.scaledAttributes()
                    ? clamp(((byte) input.rawProneness() + 2) / 5, 1, 20)
                    : input.rawProneness();
            int combined = (byte) ((10000 - input.condition()) / 1000 + prone) + (byte) prone * 4;
            int attribute = clamp(combined / 5, 1, 20);
            score = combined <= 44 ? (int) (attribute * 0.125f * 50f)
                    : (int) (((attribute - 8) / 12f) * 50f + 50f);
        }
        if (input.fatigue() >= 416) {
            int increase = (input.fatigue() - 400) / 15;
            // Native code adds both the uncapped and capped increments, then clamps a signed byte.
            score = clamp((byte) (score + increase + Math.min(increase, 25)), 0, 100);
        }
        if (input.injuryTypeIndex() != null
                && (input.injuryTypeIndex() == input.recurringFirst()
                    || input.injuryTypeIndex() == input.recurringSecond()
                    || input.injuryTypeIndex() == input.recurringThird())) {
            return 100;
        }
        return score;
    }

    /** 12E036B0 combines the physician's selected injury types and assessment flags. */
    public static int assessedSusceptibility(int primary, int secondary, int assessmentFlags) {
        int score = Math.max(primary, secondary);
        if ((assessmentFlags & 0x102) != 0) {
            return Math.max(85, (byte) (score + 30));
        }
        if ((assessmentFlags & 0x281) != 0) {
            return Math.max(70, (byte) (score + 20));
        }
        if ((assessmentFlags & 0x3050) != 0) {
            return Math.max(55, (byte) (score + 10));
        }
        return score;
    }

    /** Exact additional-risk score from 2DB5220 / 12E06590. */
    public static float physicalRisk(int condition, int sharpness, int matchLoadCategory) {
        float match = matchLoadCategory == 3 ? 1.5f : matchLoadCategory == 2 ? 2.5f : 0f;
        float sharp = sharpness < 7000 ? 3.5f : sharpness < 7500 ? 2.5f
                : sharpness <= 8499 ? 1.5f : 0f;
        float conditionRisk = condition <= 5000 ? 2.5f : condition <= 6500 ? 1.5f
                : condition <= 7000 && sharpness < 9501 ? 1.5f : 0f;
        return Math.max(match, Math.max(sharp, conditionRisk));
    }

    /**
     * Native 2DB2920 training factor matrix. Values are the nine signed, truncated
     * category workloads returned by 16CC370, not the visible training-load label.
     */
    public static float[] trainingFactors(int[] t, boolean goalkeeper) {
        if (t.length != 9) throw new IllegalArgumentException("Nine native training categories required");
        float[] factors = new float[14];
        factors[1] = goalkeeper ? t[1] : (t[2] + t[3] + t[4] + t[6] + t[7]) / 5f;
        factors[2] = goalkeeper ? (t[0] + t[1] * 4) / 5f
                : (t[0] * 2 + t[5] + t[7] + t[3]) / 5f;
        factors[3] = t[0];
        factors[4] = goalkeeper ? t[0]
                : (t[0] * 2 + t[4] + t[5] + t[2] + t[3] + t[7] + t[6]) * 0.125f;
        factors[5] = t[0];
        factors[6] = goalkeeper ? (t[0] * 3 + t[1] * 2) / 5f
                : (t[0] * 6 + t[3] + t[7]) * 0.125f;
        factors[7] = goalkeeper ? (t[1] * 3 + t[0] * 2) / 5f
                : (t[0] + t[2] + t[3] + t[4] + t[5] + t[6] + t[7]) / 7f;
        factors[8] = goalkeeper ? t[1] : (t[5] + t[7] * 2) / 3f;
        factors[9] = t[1] * 0.75f;
        factors[10] = t[1] * 0.75f;
        factors[11] = (t[3] + t[2]) * 0.5f;
        factors[12] = (t[7] + t[5]) * 0.5f;
        factors[13] = (t[2] + t[3] + t[6]) / 3f;
        return factors;
    }

    /** Scalar part of 2DB3070, preserving SSE float operations and native order. */
    public static float injuryRiskScore(int injuryFactorMask, float[] factors, RiskModifiers modifiers,
            int recurrenceCount) {
        if (factors.length != 14) throw new IllegalArgumentException("Fourteen native risk factors required");
        float maximum = 0;
        for (int i = 1; i <= 13; i++) {
            if ((injuryFactorMask & (1 << (i - 1))) != 0 && factors[i] > maximum) maximum = factors[i];
        }
        if (maximum == 0) return 0;
        float value = 0;
        if (!modifiers.noTraining()) {
            value = maximum - modifiers.reduction();
            value *= modifiers.intensity();
            value *= modifiers.roleMedical();
            value *= modifiers.generalMedical();
            value *= modifiers.individual();
            value *= modifiers.fatigue();
        }
        float score;
        if (value > 10f) {
            score = value < 20f ? ((value - 10f) / 10f) * 0.5f + 1f
                    : (value - 20f) / 10f + 1.5f;
        } else {
            score = (value / 10f) * 0.25f + 0.75f;
        }
        if (score >= 1.5f && recurrenceCount >= 0) {
            score *= recurrenceCount >= 6 ? 1.2f : recurrenceCount >= 4 ? 1.1f : 1.05f;
        }
        return score;
    }

    public record RiskModifiers(int reduction, boolean noTraining, float intensity,
            float roleMedical, float generalMedical, float individual, float fatigue) { }

    /** Resolves 2DB48C0's catalogue maximum, then the physical and tactical intensity adjustments. */
    public Result readRisk(ProcessMemoryReader r, long player, long module, LocalDate date,
            int condition, int sharpness, int fatigue, int matchLoadCategory,
            PlayerTrainingLoadReader.RiskTrainingInputs training, int[] categories,
            Float teamRiskMultiplier) throws IOException {
        PlayerSnapshotValidator.validatePlayerInjuryRiskLayout(layout);
        var calculation = PlayerInjuryRiskLayout.Calculation.current();
        PlayerSnapshotValidator.validatePlayerInjuryCalculationLayout(calculation);
        validateRiskScalars(condition, sharpness, fatigue, matchLoadCategory);
        if ((r.readU8(player + calculation.flags()) & 2) != 0) return new Result(0, riskLabel(0));
        return readRisk(r, player, module, date, condition, sharpness, fatigue, matchLoadCategory,
                training, categories, medicalAssessment.unavailable(r, player, module, date), teamRiskMultiplier);
    }

    public Result readRisk(ProcessMemoryReader r, long player, long module, LocalDate date,
            int condition, int sharpness, int fatigue, int matchLoadCategory,
            PlayerTrainingLoadReader.RiskTrainingInputs training, int[] categories,
            Boolean unavailable, Float teamRiskMultiplier) throws IOException {
        return readRisk(r, player, module, date, condition, sharpness, fatigue, matchLoadCategory,
                training, categories, unavailable, teamRiskMultiplier, PlayerInjuryRiskLayout.Calculation.current());
    }

    public Result readRisk(ProcessMemoryReader r, long player, long module, LocalDate date,
            int condition, int sharpness, int fatigue, int matchLoadCategory,
            PlayerTrainingLoadReader.RiskTrainingInputs training, int[] categories,
            Boolean unavailable, Float teamRiskMultiplier, PlayerInjuryRiskLayout.Calculation c) throws IOException {
        PlayerSnapshotValidator.validatePlayerInjuryRiskLayout(layout);
        PlayerSnapshotValidator.validatePlayerInjuryCalculationLayout(c);
        validateRiskScalars(condition, sharpness, fatigue, matchLoadCategory);
        if ((r.readU8(player + c.flags()) & 2) != 0 || Boolean.TRUE.equals(unavailable))
            return new Result(0, riskLabel(0));
        if (unavailable == null || teamRiskMultiplier == null) return null;
        if (teamRiskMultiplier != 1f && teamRiskMultiplier != 1.1f && teamRiskMultiplier != 1.2f)
            throw new IOException("Player overall risk tactical multiplier anchor failed");
        var active = medicalAssessment.activeInjury(r, player, date);
        int disabled = r.readU8(module + c.disabledRva());
        float core;
        if (disabled == 1 || disabled == 2) core = 0;
        else if (active != null && active.lightDaysRemaining(date) > 0) core = 1;
        else {
            if (training == null || categories == null) return null;
            int base = (int) (training.roleMedicalScore() * 1.176470637f);
            RiskModifiers modifiers = new RiskModifiers((byte) (int) (training.focus()[0] * 0.15f + 1f),
                    training.individualIntensity() == 254,
                    training.individualIntensity() == 0 ? 0.75f : training.individualIntensity() == 2 ? 1.25f : 1f,
                    training.roleMedicalScore() / 85f, training.generalMedicalScore() / 85f,
                    base > 0 ? training.modifiedBaseline() / (float) base : 1f,
                    fatigue >= 800 ? 1.1f : fatigue >= 600 ? 1.05f : fatigue >= 400 ? 1.025f : 1f);
            int goalkeeper = r.readU8(player + PlayerInjuryRiskLayout.TrainingCategories.current().goalkeeper());
            if (goalkeeper > 20) throw new IOException("Player injury risk goalkeeper anchor failed");
            float[] factors = trainingFactors(categories, goalkeeper > 14);
            long manager = r.readU64(module + c.managerRva());
            if (manager == 0) return null;
            long vb = r.readU64(player + c.personVirtualBase());
            int personIndex = r.readI32(player + r.readI32(vb + c.personDisplacement()) + c.personIndex());
            int previousType = -1;
            long[] previous = boundedVector(r, manager, c.previousBegin(), c.previousEnd(), 100000);
            for (long at = previous[0]; at < previous[1]; at += 8) {
                long entry = r.readU64(at);
                if (r.readI32(entry + c.previousPlayerIndex()) == personIndex) {
                    previousType = r.readI16(entry + c.previousInjuryIndex());
                    break;
                }
            }
            long descriptor = r.readU64(module + c.catalogRva());
            if (descriptor == 0) return null;
            long header = r.readU64(descriptor + c.registryHeader());
            if (header == 0) return null;
            long[] catalog = boundedVector(r, header, c.vectorBegin(), c.vectorEnd(), 32768);
            boolean recovering = false;
            if (active != null) {
                long medical = r.readU64(player + layout.injuryReferenceRel());
                long[] injuries = boundedVector(r, medical, c.vectorBegin(), c.vectorEnd(), 128);
                for (long at = injuries[0]; at < injuries[1]; at += 8)
                    if (r.readU8(r.readU64(at) + c.recoveryState()) == 4) { recovering = true; break; }
            }
            core = 0.1f;
            for (long at = catalog[0]; at < catalog[1]; at += 8) {
                long type = r.readU64(at);
                int typeIndex = r.readI32(type + layout.injuryTypeIndexRel());
                if (typeIndex < 0 || typeIndex > 32767) throw new IOException("Player risk catalogue index anchor failed");
                int recurrenceCount = -1;
                for (int i = 0; i < 3; i++) {
                    long recurring = player + layout.recurringInjuriesRel() + (long) i * layout.recurringInjuryStride();
                    if (r.readI16(recurring) == typeIndex) recurrenceCount = r.readU8(recurring + c.recurringCount());
                }
                float score = injuryRiskScore(r.readU16(type + c.injuryFactorMask()), factors, modifiers, recurrenceCount);
                if (score == 0) continue;
                if (typeIndex == previousType) score += 1f;
                if (active != null && active.type() == type) {
                    if (recovering) score += 1.55f;
                    if (active.susceptibilityRecoveryComplete(date)) score = Math.max(1.5f, score + 1f);
                }
                core = Math.max(core, score);
            }
        }
        float score = Math.max(core, physicalRisk(condition, sharpness, matchLoadCategory));
        score *= teamRiskMultiplier;
        return new Result(score, riskLabel(score));
    }

    private static long[] boundedVector(ProcessMemoryReader r, long owner, int beginOffset, int endOffset,
            int limit) throws IOException {
        long begin = r.readU64(owner + beginOffset);
        long end = r.readU64(owner + endOffset);
        if (begin < 0 || end < begin || end - begin > limit * 8L || (end - begin) % 8 != 0)
            throw new IOException("Player injury risk vector range anchor failed");
        return new long[]{begin, end};
    }

    public record Result(float score, String label) { }

    private static void validateRiskScalars(int condition, int sharpness, int fatigue, int matchLoadCategory)
            throws IOException {
        if (condition < 0 || condition > 10000 || sharpness < 0 || sharpness > 10000
                || fatigue < -1000 || fatigue > 1000 || matchLoadCategory < 0 || matchLoadCategory > 3)
            throw new IOException("Player overall risk scalar range anchor failed");
    }

    public SusceptibilityResult readSusceptibility(ProcessMemoryReader r, long player, long module,
            LocalDate date, int condition, int fatigue, PlayerMedicalAssessmentReader.StaffContext staff)
            throws IOException {
        PlayerSnapshotValidator.validatePlayerInjuryRiskLayout(layout);
        var assessment = medicalAssessment.read(r, player, module, date, staff);
        if (!assessment.resolved()) return new SusceptibilityResult(null,
                assessment.guaranteedSusceptibilityLabel(), assessment.unresolvedReason());
        int primary = baseSusceptibility(readSusceptibilityInputs(r, player, condition, fatigue,
                assessment.primaryType()));
        int secondary = assessment.secondaryType() == 0 ? 0
                : baseSusceptibility(readSusceptibilityInputs(r, player, condition, fatigue, assessment.secondaryType()));
        int score = assessedSusceptibility(primary, secondary, assessment.flags());
        return new SusceptibilityResult(score, susceptibilityLabel(score), null);
    }

    public record SusceptibilityResult(Integer score, String label, String unresolvedReason) { }

    public int[] readTrainingCategories(ProcessMemoryReader r, long player, long module,
            PlayerTrainingLoadReader.RiskTrainingInputs training, int role, int[] attributeIds) throws IOException {
        return readTrainingCategories(r, player, module, training, role, attributeIds,
                PlayerInjuryRiskLayout.TrainingCategories.current());
    }

    public int[] readTrainingCategories(ProcessMemoryReader r, long player, long module,
            PlayerTrainingLoadReader.RiskTrainingInputs training, int role, int[] attributeIds,
            PlayerInjuryRiskLayout.TrainingCategories l) throws IOException {
        PlayerSnapshotValidator.validatePlayerInjuryTrainingLayout(l);
        if (training == null || attributeIds == null) return null;
        if (role < -1 || role > 3 || attributeIds.length > 128)
            throw new IOException("Player injury training role/attribute range anchor failed");
        long vb = r.readU64(player + l.virtualBase());
        long attributes = player + r.readI32(vb + l.attributesDisplacement());
        int professionalism = (byte) r.readU8(attributes + l.professionalism());
        int ambition = (byte) r.readU8(attributes + l.ambition());
        int goalkeeper = r.readU8(player + l.goalkeeper());
        if (professionalism < 0 || professionalism > 20 || ambition < 0 || ambition > 20 || goalkeeper > 20)
            throw new IOException("Player injury training personality/position range anchor failed");
        int[] sizes = new int[9];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = (byte) r.readU8(module + l.categorySizesRva() + (long) i * l.categorySizeStride());
            if (sizes[i] < 1 || sizes[i] > 18)
                throw new IOException("Player injury training category-size range anchor failed");
        }
        int[] categories = new int[attributeIds.length];
        for (int i = 0; i < categories.length; i++)
            categories[i] = classifyTrainingAttribute(r, module, attributeIds[i], role == 0, l);
        TrainingSession[] sessions = new TrainingSession[21];
        if (training.schedule() != 0) {
            long begin = r.readU64(training.manager() + l.managerDefinitions());
            long end = r.readU64(training.manager() + l.managerDefinitions() + 8);
            if (begin < 0 || end < begin || end - begin > 4096 * 8L || (end - begin) % 8 != 0)
                throw new IOException("Player injury training definition vector range anchor failed");
            for (int day = 0; day < 7; day++) for (int session = 0; session < 3; session++) {
                int code = r.readU16(training.schedule() + (long) day * l.dayStride()
                        + l.firstSession() + session * 2L);
                if ((training.personalDayMask() & (1 << day)) != 0 && isExcludedOnPersonalDay(r, module, code))
                    continue;
                for (long at = begin; at < end; at += 8) {
                    long definition = r.readU64(at);
                    if (r.readU16(definition + l.definitionCode()) != code) continue;
                    boolean present = role >= 0 && r.readU64(definition + l.roleAttributesBegin()
                            + (long) role * l.roleAttributesStride()) != r.readU64(definition + l.roleAttributesEnd()
                            + (long) role * l.roleAttributesStride());
                    float[] workloads = new float[9];
                    if (present) for (int i = 0; i < workloads.length; i++) {
                        workloads[i] = Float.intBitsToFloat(r.readI32(definition + l.definitionCategories()
                                + (long) role * l.roleCategoryStride() + i * 4L));
                        if (!Float.isFinite(workloads[i]) || workloads[i] < -100 || workloads[i] > 100)
                            throw new IOException("Player injury training session category range anchor failed");
                    }
                    int individualFlag = role < 0 ? 0 : r.readU8(definition + l.roleIndividualFlag() + role);
                    if (individualFlag > 1)
                        throw new IOException("Player injury training individual flag range anchor failed");
                    sessions[day * 3 + session] = new TrainingSession(code, false, present,
                            individualFlag != 0, workloads);
                    break;
                }
            }
        }
        return trainingCategories(sessions, role, categories, sizes, training.individualIntensity(),
                goalkeeper > 14, professionalism, ambition);
    }

    /** Native 2D58610 uses a two-stage jump table and a goalkeeper byte table. */
    public static int classifyTrainingAttribute(ProcessMemoryReader r, long module, int attribute,
            boolean goalkeeper, PlayerInjuryRiskLayout.TrainingCategories l) throws IOException {
        PlayerSnapshotValidator.validatePlayerInjuryTrainingLayout(l);
        if (attribute < 15 || attribute > 68) return -1;
        long first = module + l.firstMapRva();
        long target = first + r.readI32(first + (attribute - 15) * 4L);
        int instruction = r.readU16(target);
        if (instruction == 0xD284) {
            if (attribute < 22 || attribute > 68) return -1;
            if (goalkeeper) {
                int category = r.readU8(module + l.goalkeeperMapRva() + attribute - 22);
                if (category == 255) return -1;
                if (category > 8) throw new IOException("Player injury training attribute-map range anchor failed");
                return category;
            }
            long second = module + l.outfieldMapRva();
            target = second + r.readI32(second + (attribute - 22) * 4L);
            instruction = r.readU16(target);
        }
        if ((instruction & 255) == 0xB0) {
            int value = instruction >>> 8;
            if (value == 255) return -1;
            if (value <= 8) return value;
        } else if (instruction == 0xC031) return 0;
        throw new IOException("Player injury training classifier code anchor failed");
    }

    /** Native 16CE900 distinguishes sessions skipped on a personal rest day. */
    public static boolean isExcludedOnPersonalDay(ProcessMemoryReader r, long module, int sessionCode)
            throws IOException {
        var l = PlayerInjuryRiskLayout.TrainingCategories.current();
        PlayerSnapshotValidator.validatePlayerInjuryTrainingLayout(l);
        if (sessionCode < 0 || sessionCode > 0x142) return false;
        long table = module + l.personalDayExclusionsRva();
        long leaf = table + r.readI32(table + sessionCode * 4L);
        int first = r.readU8(leaf);
        if (first == 0xC3) return false;
        if (first == 0xB0 && r.readU8(leaf + 1) == 1) return true;
        throw new IOException("Player injury personal-day session code anchor failed");
    }

    /** Native 1680FF0 category arithmetic, after resolving the role and attribute selection. */
    public static int[] trainingCategories(TrainingSession[] sessions, int role, int[] individualCategories,
            int[] categorySizes, int intensity, boolean goalkeeper, int professionalism, int ambition) {
        if (sessions.length != 21 || categorySizes.length != 9)
            throw new IllegalArgumentException("Twenty-one sessions and nine category sizes required");
        float[] totals = new float[9];
        float[] individual = new float[9];
        float individualScale = Math.max(0.05f, (individualCategories.length - 1) * -0.05f + 1f);
        for (int category : individualCategories) {
            if (category < 0 || category > 8) continue;
            if (categorySizes[category] <= 0) throw new IllegalArgumentException("Empty training category");
            individual[category] += (20f / categorySizes[category]) * individualScale;
        }
        float sessionCount = 0;
        for (TrainingSession session : sessions) {
            if (session == null || session.excluded()) continue;
            boolean validRole = role >= 0 && role < 4;
            boolean ordinary = validRole && (session.roleAttributesPresent() || session.individualTraining());
            if (!ordinary && session.code() != 200) continue;
            if (validRole && session.roleAttributesPresent()) {
                if (session.categoryWorkloads().length != 9)
                    throw new IllegalArgumentException("Nine session category workloads required");
                addCategories(totals, session.categoryWorkloads(), role == 0);
            }
            addCategories(totals, individual, role == 0);
            sessionCount += ordinary ? 1f : 2f;
        }
        float multiplier = intensity == 0 || intensity == 254 ? 0.75f : intensity == 2 ? 1.25f : 1f;
        for (int i = 0; i < totals.length; i++) totals[i] *= multiplier;
        float personality = (professionalism + ambition - 20) * 0.15f;
        totals[0] = raiseNative(totals[0], personality + 2.5f);
        if (sessionCount > 0) {
            totals[1] = raiseNative(totals[1], goalkeeper ? sessionCount * 2.5f + personality : 0);
            for (int i = 2; i < totals.length; i++)
                totals[i] = raiseNative(totals[i], goalkeeper ? 0 : sessionCount + personality);
        }
        int[] result = new int[9];
        for (int i = 0; i < result.length; i++) {
            int rounded = clamp((int) (totals[i] + 0.5f), 0, 25);
            result[i] = rounded == 0 && totals[i] >= 1f ? 1 : rounded;
        }
        return result;
    }

    private static float raiseNative(float current, float minimum) {
        return minimum > current ? (minimum - current) + current : current;
    }

    private static void addCategories(float[] target, float[] addition, boolean goalkeeperRole) {
        target[0] += addition[0];
        if (goalkeeperRole) target[1] += addition[1];
        else for (int i = 2; i < target.length; i++) target[i] += addition[i];
    }

    public record TrainingSession(int code, boolean excluded, boolean roleAttributesPresent,
            boolean individualTraining, float[] categoryWorkloads) { }

    public static String susceptibilityLabel(int value) {
        return value < 15 ? "Very Low" : value < 30 ? "Low" : value < 45 ? "Below Average"
                : value < 55 ? "Average" : value < 70 ? "Above Average" : value < 85 ? "High" : "Very High";
    }

    public static String riskLabel(float value) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Invalid overall injury risk");
        }
        return value < 0.95f ? "Low" : value < 1.5f ? "Normal" : value < 2.5f ? "Increased"
                : value < 3.5f ? "High" : "Very High";
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    public record SusceptibilityInputs(int rawProneness, boolean scaledAttributes, int condition,
            int fatigue, Integer injuryTypeIndex, int injuryTypeKind, int injuryTypeOccurrence,
            int recurringFirst, int recurringSecond, int recurringThird) { }
}
