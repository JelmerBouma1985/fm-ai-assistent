package com.github.fmaiassistent.config;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import liquibase.change.ColumnConfig;
import liquibase.change.core.*;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.Arrays;

@ImportRuntimeHints(NativeHintsConfig.LiquibaseNativeHints.class)
@Configuration(proxyBeanMethods = false)
public class NativeHintsConfig {

    static class LiquibaseNativeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.reflection().registerType(LoadDataChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

            hints.reflection().registerType(CreateTableChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(AddColumnChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(DropColumnChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(DropTableChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(CreateIndexChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(DropIndexChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(AddForeignKeyConstraintChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(AddNotNullConstraintChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(AddPrimaryKeyChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(DropPrimaryKeyChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(AddUniqueConstraintChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(ModifyDataTypeChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(InsertDataChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(UpdateDataChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(RawSQLChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(SQLFileChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(CreateViewChange.class, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            hints.reflection().registerType(LoadDataColumnConfig.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(ColumnConfig.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);

            registerFields(hints, PlayerEntity.class);
            registerFields(hints, StaffEntity.class);
            registerFields(hints, ClubEntity.class);
            registerFields(hints, CompetitionEntity.class);

            // Caffeine selects this bounded, access-expiring cache implementation by
            // class name. Its upstream reachability metadata currently omits the variant.
            hints.reflection().registerType(
                    TypeReference.of("com.github.benmanes.caffeine.cache.SSMSA"),
                    type -> type.withField("FACTORY")
                            .withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
            hints.reflection().registerType(
                    TypeReference.of("com.github.benmanes.caffeine.cache.SSSMSA"),
                    type -> type.withField("FACTORY")
                            .withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
            hints.reflection().registerType(
                    TypeReference.of("com.github.benmanes.caffeine.cache.PSAMS"),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

            hints.resources().registerPattern("db/changelog/**");
        }

        private static void registerFields(RuntimeHints hints, Class<?> type) {
            Arrays.stream(type.getDeclaredFields()).forEach(hints.reflection()::registerField);
        }
    }
}
