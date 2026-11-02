package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerTrainingLoadLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerTrainingLoadReaderTest {
    private static final long MODULE = 0x7FFD90CC0000L;
    private static final LocalDate DATE = LocalDate.of(2026,8,3);

    @Test
    void replaysCapturedNativeScheduleAndHumanIntensityAsMedium() throws Exception {
        var memory = captured();
        var result = new PlayerTrainingLoadReader().read(memory, memory.player, MODULE, DATE);
        assertThat(result).isNotNull();
        assertThat(result.label()).isEqualTo("Medium");
        assertThat(result.workload()).isEqualTo(62);
        assertThat(result.baseline()).isEqualTo(62);
        assertThat(result.intensity()).isEqualTo(1);
    }

    @Test
    void everyShiftedLayoutMemberRejectsTheSameCapturedFixture() throws Exception {
        var memory = captured();
        assertThat(new PlayerTrainingLoadReader().read(memory, memory.player, MODULE, DATE).workload()).isEqualTo(62);
        var layout = PlayerTrainingLoadLayout.current();
        for (RecordComponent group : layout.getClass().getRecordComponents()) {
            Object record = group.getAccessor().invoke(layout);
            for (RecordComponent member : record.getClass().getRecordComponents()) {
                Object value = member.getAccessor().invoke(record);
                Object shifted;
                if (value instanceof Integer number) shifted = Integer.valueOf(number + 1);
                else shifted = Long.valueOf(((Long) value) + 1);
                var altered = (PlayerTrainingLoadLayout) replace(layout, group.getName(), replace(record, member.getName(), shifted));
                assertThatThrownBy(() -> new PlayerTrainingLoadReader(altered).read(memory, memory.player, MODULE, DATE))
                        .as(group.getName() + "." + member.getName()).isInstanceOf(IOException.class)
                        .hasMessageContaining("layout anchor");
            }
        }
    }

    @Test
    void automaticIntensityUsesSharpnessBandsAndPreservesSignedWorkload() throws Exception {
        var memory = captured();
        var reader = new PlayerTrainingLoadReader();
        long configuration = reader.read(memory, memory.player, MODULE, DATE).configuration();
        // The capture only read the current sharpness band's slot. Supply explicit
        // alternate-band inputs for this branch test without pretending they were captured.
        memory.put(configuration + 0x61, 0, 1);
        memory.put(configuration + 0x60, 254, 1);
        memory.put(memory.player + 0x25C, 5050, 2);
        var half = reader.read(memory, memory.player, MODULE, DATE);
        assertThat(half.intensity()).isZero();
        assertThat(half.workload()).isEqualTo(37);
        assertThat(half.label()).isEqualTo("Light");
        memory.put(memory.player + 0x25C, 4500, 2);
        var rest = reader.read(memory, memory.player, MODULE, DATE);
        assertThat(rest.intensity()).isEqualTo(254);
        assertThat(rest.workload()).isEqualTo(12);
        assertThat(rest.label()).isEqualTo("Very Light");
    }

    @Test
    void extraTrainingAndPersonalityAreIncludedWithoutClampingZeroPersonality() throws Exception {
        var memory = captured();
        long vb = memory.readU64(memory.player + 8);
        long attributes = memory.player + memory.readI32(vb + 8);
        memory.put(attributes + 0x54, 20, 1);
        memory.put(attributes + 0x51, 20, 1);
        assertThat(new PlayerTrainingLoadReader().read(memory, memory.player, MODULE, DATE).workload()).isEqualTo(65);
        memory.put(attributes + 0x54, 0, 1);
        memory.put(attributes + 0x51, 0, 1);
        memory.put(memory.player + 0x1A8, 0x100000, 8);
        memory.put(0x100000, 0x200000, 8);
        memory.put(0x100008, 0x200018, 8);
        memory.put(0x200008, 1, 2);
        memory.put(0x20000A, 5, 2);
        memory.put(0x200017, 0, 1);
        assertThat(new PlayerTrainingLoadReader().read(memory, memory.player, MODULE, DATE).workload()).isEqualTo(72);
        memory.put(0x200017, 1, 1);
        assertThat(new PlayerTrainingLoadReader().read(memory, memory.player, MODULE, DATE).workload()).isEqualTo(62);
    }

    @Test
    void absentWeekAndUnsupportedPlatformStayUnknownAndOversizedVectorsReject() throws Exception {
        var memory = captured();
        var reader = new PlayerTrainingLoadReader();
        long configuration = reader.read(memory, memory.player, MODULE, DATE).configuration();
        long weeks = memory.readU64(configuration + 0x20);
        // Retain the four weeks actually visited by the bounded capture.
        memory.put(configuration + 0x28, weeks + 4 * Long.BYTES, Long.BYTES);
        assertThat(reader.read(memory, memory.player, MODULE, DATE.plusMonths(6))).isNull();
        memory.platform = ProcessMemoryReader.Platform.LINUX;
        assertThat(reader.read(memory, memory.player, MODULE, DATE)).isNull();
        memory.platform = ProcessMemoryReader.Platform.WINDOWS;
        long manager = memory.readU64(MODULE + 0x56F23B0);
        memory.put(manager + 0x70, Long.MAX_VALUE, 8);
        assertThatThrownBy(() -> reader.read(memory, memory.player, MODULE, DATE)).isInstanceOf(IOException.class)
                .hasMessageContaining("vector range");
    }

    @Test
    void nativeNotTrainingFlagReturnsNoneWithoutRequiringSchedule() throws Exception {
        var memory = captured();
        memory.put(memory.player + 0x1EE, 2, 1);
        var result = new PlayerTrainingLoadReader().read(memory, memory.player, MODULE, DATE);
        assertThat(result.label()).isEqualTo("None");
        assertThat(result.workload()).isZero();
    }

    @Test
    void medicalInputsUseUnscaledScheduleAndSignedSessionEffects() throws Exception {
        var memory = captured();
        var inputs = new PlayerTrainingLoadReader().riskInputs(memory, memory.player, MODULE, DATE);
        assertThat(inputs.roleMedicalScore()).isEqualTo(53);
        assertThat(inputs.generalMedicalScore()).isEqualTo(53);
        assertThat(inputs.individualIntensity()).isEqualTo(1);
        assertThat(inputs.modifiedBaseline()).isEqualTo(62);
        assertThat(inputs.focus()).containsExactly((byte)-10,(byte)11,(byte)7,(byte)-11,(byte)17,(byte)6);
        byte[] copy = inputs.focus();
        copy[0]=100;
        assertThat(inputs.focus()[0]).isNotEqualTo((byte)100);
        memory.put(memory.player + 0x1EE, 0x80, 1);
        long contexts = memory.readU64(inputs.manager()+PlayerTrainingLoadLayout.current().manager().contexts());
        long context = memory.readU64(contexts);
        memory.put(context+PlayerTrainingLoadLayout.current().restCalendar().records(),0,8);
        memory.put(context+PlayerTrainingLoadLayout.current().restCalendar().records()+8,0,8);
        assertThat(new PlayerTrainingLoadReader().riskInputs(memory,memory.player,MODULE,DATE).personalDayMask()).isZero();
    }

    @Test
    void personalRestIntervalsUsePersonIdentityAndExclusiveEndDateAndSkipPhysicalSessions() throws Exception {
        var memory=captured();
        var reader=new PlayerTrainingLoadReader();
        var layout=PlayerTrainingLoadLayout.current();
        long manager=memory.readU64(MODULE+layout.globals().manager());
        long contexts=memory.readU64(manager+layout.manager().contexts());
        long context=memory.readU64(contexts);
        memory.put(memory.player+layout.player().flags(),0x80,1);
        memory.put(context+layout.restCalendar().records(),0x700000,8);
        memory.put(context+layout.restCalendar().records()+8,0x700008,8);
        memory.put(0x700000,0x710000,8);
        long vb=memory.readU64(memory.player+layout.player().virtualBase());
        long person=memory.player+layout.player().virtualBase()+memory.readI32(vb+layout.player().personDisplacement());
        var calendar=layout.restCalendar();
        memory.put(0x710000+calendar.person(),person,8);
        memory.put(0x710000+calendar.startDay(),DATE.getDayOfYear(),2);
        memory.put(0x710000+calendar.startYear(),DATE.getYear(),2);
        memory.put(0x710000+calendar.endDay(),DATE.plusDays(1).getDayOfYear(),2);
        memory.put(0x710000+calendar.endYear(),DATE.getYear(),2);
        assertThat(reader.personalDayMask(memory,memory.player,MODULE,DATE)).isEqualTo(1);
        long table=MODULE+com.github.fmaiassistent.memory.PlayerInjuryRiskLayout.TrainingCategories.current().personalDayExclusionsRva();
        long trueLeaf=MODULE+0x100000;
        memory.put(trueLeaf,0x01B0,2);
        for(int code:new int[]{4,6,7})memory.put(table+code*4L,trueLeaf-table,4);
        var filtered=reader.riskInputs(memory,memory.player,MODULE,DATE);
        assertThat(filtered.personalDayMask()).isEqualTo(1);
        assertThat(filtered.focus()).containsExactly((byte)-7,(byte)14,(byte)7,(byte)-14,(byte)17,(byte)3);
        memory.put(0x710000+calendar.person(),person+8,8);
        assertThat(reader.personalDayMask(memory,memory.player,MODULE,DATE)).isZero();
    }

    @Test
    void replaysTacticIntensityAndAllTrainingRiskInputsFromFreshProcessCapture() throws Exception {
        var memory = captured("/fm27/player-training-risk-captured.json");
        var reader = new PlayerTrainingLoadReader();
        assertThat(reader.trainingRole(memory,memory.player,memory.module)).isEqualTo(2);
        assertThat(reader.preferredPositionMask(memory,memory.player,memory.module,DATE)).isEqualTo(128);
        assertThat(reader.preferredAttributeRoleOverride(memory,memory.player,memory.module,DATE)).isNull();
        assertThat(reader.tacticIntensity(memory,memory.tactic)).isEqualTo(42);
        assertThat(reader.teamRiskMultiplier(memory,memory.player,memory.module)).isEqualTo(1.1f);
        assertThat(reader.riskInputs(memory,memory.player,memory.module,DATE).modifiedBaseline()).isEqualTo(62);
    }

    @Test
    void preferredPositionRequiresThirtyDaysAndExplicitPersonalRoleOverridesDefaultScoring() throws Exception {
        var memory = captured("/fm27/player-training-risk-captured.json");
        var reader = new PlayerTrainingLoadReader();
        var layout = PlayerTrainingLoadLayout.current();
        long vb = memory.readU64(memory.player + layout.player().virtualBase());
        long attrs = memory.player + memory.readI32(vb + layout.player().attributesDisplacement());
        memory.put(memory.player + layout.preferredPosition().cache(),0x100000,8);
        memory.put(0x100000,0x200000,8); memory.put(0x100008,0x200004,8);
        memory.put(0x200002,3,1); memory.put(0x200003,3,1);
        memory.put(attrs + layout.preferredPosition().startYear(),DATE.minusDays(29).getYear(),2);
        memory.put(attrs + layout.preferredPosition().startDay(),DATE.minusDays(29).getDayOfYear(),2);
        assertThat(reader.preferredPositionMask(memory,memory.player,memory.module,DATE)).isEqualTo(128);
        memory.put(attrs + layout.preferredPosition().startDay(),DATE.minusDays(30).getDayOfYear(),2);
        memory.put(memory.module + layout.preferredPosition().positionFlags()+3*4,0x10,4);
        assertThat(reader.preferredPositionMask(memory,memory.player,memory.module,DATE)).isEqualTo(0x10);
        memory.put(memory.player + layout.player().extra(),0x300000,8);
        memory.put(0x300000,0x400000,8); memory.put(0x300008,0x400018,8);
        memory.put(0x400008,0,2); memory.put(0x40000A,5,2); memory.put(0x400017,0,1);
        memory.put(memory.module + layout.role().extraPositionTable()+15*4,0x100400,4);
        memory.put(memory.module + layout.preferredPosition().attributeRoles()+31*8,0x4000000000L,8);
        assertThat(reader.preferredPositionMask(memory,memory.player,memory.module,DATE)).isEqualTo(0x100400);
        assertThat(reader.preferredAttributeRoleOverride(memory,memory.player,memory.module,DATE)).isEqualTo(0x4000000000L);
        memory.put(0x200002,0,1);
        memory.put(memory.module + layout.preferredPosition().positionFlags(),1,4);
        assertThat(reader.preferredPositionMask(memory,memory.player,memory.module,DATE)).isEqualTo(1);
        assertThat(reader.preferredAttributeRoleOverride(memory,memory.player,memory.module,DATE)).isNull();
    }

    @Test
    void tacticFlagsAndInstructionDefaultsChangeRiskBucketWithoutReadingGameCode() throws Exception {
        var memory = captured("/fm27/player-training-risk-captured.json");
        var reader = new PlayerTrainingLoadReader();
        var layout = PlayerTrainingLoadLayout.current();
        long synthetic = 0x600000;
        for (int i=0;i<0x800;i++) memory.put(synthetic+i,0,1);
        memory.put(synthetic + layout.tactic().mentality(),4,1);
        assertThat(reader.tacticIntensity(memory,synthetic)).isEqualTo(6);
        memory.put(synthetic + layout.tactic().mentality(),7,1);
        assertThat(reader.tacticIntensity(memory,synthetic)).isEqualTo(31);
        memory.put(synthetic + layout.tactic().settings(),(1L<<61)|(1L<<42)|(1L<<54),8);
        memory.put(synthetic + layout.tactic().extendedSettings(),(1<<1)|(1<<8)|(1<<6),8);
        assertThat(reader.tacticIntensity(memory,synthetic)).isEqualTo(76);
        long team = memory.readU64(memory.player + layout.player().currentTeam());
        long node = reader.teamContext(memory,team,memory.module);
        long begin = memory.readU64(node + layout.role().tactics());
        memory.put(begin,synthetic,8);
        memory.put(memory.module + layout.tactic().highRisk(),Float.floatToIntBits(1.2f),4);
        assertThat(reader.teamRiskMultiplier(memory,memory.player,memory.module)).isEqualTo(1.2f);
        memory.put(node + layout.role().selectedTactic(),3,1);
        assertThat(reader.teamRiskMultiplier(memory,memory.player,memory.module)).isEqualTo(1f);
    }

    private static Object replace(Object record, String name, Object replacement) throws Exception {
        var components = record.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i=0;i<components.length;i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals(name) ? replacement : components[i].getAccessor().invoke(record);
        }
        return record.getClass().getDeclaredConstructor(types).newInstance(values);
    }

    private static CapturedMemory captured() throws Exception {
        return captured("/fm27/player-training-load-captured.json");
    }

    private static CapturedMemory captured(String resource) throws Exception {
        byte[] raw;
        try (var source = PlayerTrainingLoadReaderTest.class.getResourceAsStream(resource)) {
            if (source == null) throw new IOException("Missing captured training fixture");
            raw = source.readNBytes(65537);
        }
        if (raw.length > 65536) throw new IOException("Captured training fixture exceeds cap");
        var row = JsonMapper.builder().build().readTree(raw).get(0);
        var memory = new CapturedMemory(row.path("player").asLong());
        if (row.has("module")) memory.module = row.path("module").asLong();
        if (row.has("tactic")) memory.tactic = row.path("tactic").asLong();
        row.path("memory").properties().forEach(entry -> {
            long address = Long.parseLong(entry.getKey().split(":")[0]);
            byte[] bytes = HexFormat.of().parseHex(entry.getValue().asString());
            for (int i=0;i<bytes.length;i++) memory.bytes.put(address+i, bytes[i]);
        });
        return memory;
    }

    private static final class CapturedMemory implements ProcessMemoryReader {
        final long player;
        long module = MODULE, tactic;
        final Map<Long,Byte> bytes = new HashMap<>();
        Platform platform = Platform.WINDOWS;
        CapturedMemory(long player) { this.player = player; }
        void put(long address, long value, int size) {
            for (int i=0;i<size;i++) bytes.put(address+i,(byte)(value >>> (i*8)));
        }
        public int pid() { return 13340; }
        public Platform platform() { return platform; }
        public List<MemoryRegion> maps() { return List.of(); }
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] out = new byte[size];
            for (int i=0;i<size;i++) {
                Byte value=bytes.get(address+i);
                if(value==null) throw new IOException("Uncaptured training address " + Long.toHexString(address+i));
                out[i]=value;
            }
            return out;
        }
        public void close() {}
    }
}
