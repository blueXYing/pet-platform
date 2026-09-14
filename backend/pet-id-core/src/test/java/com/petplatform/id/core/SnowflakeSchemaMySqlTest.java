package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class SnowflakeSchemaMySqlTest {
    @Test void realMysqlRejectsInvalidRowsAndLeavesVirginFactUnchanged() throws Exception {
        try (var database = new MySqlIdTestDatabase()) {
            database.seedVirgin(17);
            List<String> mutations = List.of(
                    "node_id=1024",
                    "format_identity='different-epoch'",
                    "enabled=2",
                    "initialization_ref=NULL",
                    "initialization_ref='   '",
                    "fence=-1",
                    "reserved_through=-2",
                    "reserved_through=2199023255552",
                    "grant_start=NULL,grant_through=1,reserved_through=1",
                    "grant_start=5,grant_through=3,reserved_through=3",
                    "grant_start=1,grant_through=3,reserved_through=4",
                    "owner_incarnation=UUID_TO_BIN(UUID())",
                    "lease_until=NOW(3)",
                    "owner_incarnation=UUID_TO_BIN(UUID()),lease_until=NOW(3),grant_start=1,grant_through=3,reserved_through=3"
            );
            for (String mutation : mutations) {
                assertThrows(DataAccessException.class, () -> database.jdbc().update(
                        "UPDATE snowflake_worker_state SET " + mutation + " WHERE node_id=17"), mutation);
                assertEquals(-1, database.highWater(17), "CHECK failure must not partly change the row");
            }
            assertThrows(DataAccessException.class, () -> database.seedVirgin(17), "node primary key is real DB uniqueness");
            database.virginVerifier(17).verify(new PreviousJvmExitVerifier.PreviousJvm(
                    17, null, 0, database.initializationRef(17), -1));
            assertThrows(IllegalStateException.class, () -> database.virginVerifier(17).verify(
                    new PreviousJvmExitVerifier.PreviousJvm(17, null, 0, "some-other-fixture", -1)));
            assertThrows(IllegalStateException.class, () -> database.virginVerifier(17).verify(
                    new PreviousJvmExitVerifier.PreviousJvm(17, null, 0, database.initializationRef(17), 1)));
        }
    }
}
