package com.github.fmaiassistent.repository;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentRoleCatalogMigrationTest {
    @Test void existingSeedChecksumAndStaffColumnsUpgradeWithoutDuplicatingOrDroppingRoles() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:current-role-upgrade");
             var statement = connection.createStatement();
             var migration = new Liquibase("db/changelog/db.changelog-master.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            migration.update(new Contexts(), new LabelExpression());
            long roles;
            long attributes;
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM fm_role")) { rows.next(); roles = rows.getLong(1); }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM fm_role_attribute")) { rows.next(); attributes = rows.getLong(1); }
            assertThat(roles).isPositive();
            // Simulate the previous schema's completed seed and pre-existing staff columns.
            statement.executeUpdate("UPDATE databasechangelog SET md5sum='9:f8fb2b4ea8256e59d9d23df022c8ee87' WHERE id='002-baseline-role-attribute-data'");
            statement.executeUpdate("DELETE FROM databasechangelog WHERE id IN ('008-staff-role-attributes','030-current-role-catalog')");
            statement.executeUpdate("UPDATE fm_role SET game='Previous release'");
            statement.executeUpdate("UPDATE fm_role_attribute_import SET game='Previous release'");
            connection.commit();
            migration.update(new Contexts(), new LabelExpression());
            migration.update(new Contexts(), new LabelExpression());
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM fm_role WHERE game='Football Manager 27'")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(roles);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM fm_role_attribute")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(attributes);
            }
            try (var rows = statement.executeQuery("SELECT authority,goalkeeping FROM staff")) {
                assertThat(rows.getMetaData().getColumnCount()).isEqualTo(2);
            }
        }
    }
}
