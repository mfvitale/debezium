/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.binlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.binlog.util.TestHelper;
import io.debezium.connector.binlog.util.UniqueDatabase;
import io.debezium.doc.FixFor;

/**
 * @author Chris Cranford
 */
public abstract class BinlogDateTimeInKeyIT<C extends SourceConnector> extends AbstractBinlogConnectorIT<C> {

    private static final Path SCHEMA_HISTORY_PATH = Files.createTestingPath("file-schema-history-decimal-column.txt")
            .toAbsolutePath();
    private final UniqueDatabase DATABASE = TestHelper.getUniqueDatabase("pkdb", "datetime_key_test")
            .withDbHistoryPath(SCHEMA_HISTORY_PATH);

    private Configuration config;

    @Before
    public void beforeEach() {
        stopConnector();
        DATABASE.createAndInitialize();
        initializeConnectorTestFramework();
        Files.delete(SCHEMA_HISTORY_PATH);
    }

    @After
    public void afterEach() {
        try {
            stopConnector();
        }
        finally {
            Files.delete(SCHEMA_HISTORY_PATH);
        }
    }

    @Test
    @FixFor("DBZ-1194")
    public void shouldAcceptAllZeroDatetimeInPrimaryKey() throws SQLException, InterruptedException {
        // Use the DB configuration to define the connector's configuration ...
        config = DATABASE.defaultConfig()
                .with(BinlogConnectorConfig.SNAPSHOT_MODE, BinlogConnectorConfig.SnapshotMode.INITIAL)
                .build();

        // Start the connector ...
        start(getConnectorClass(), config);

        // Testing.Debug.enable();
        final int numDatabase = 3;
        final int numTables = 2;
        final int numInserts = 1;
        final int numOthers = 1; // SET
        SourceRecords records = consumeRecordsByTopic(numDatabase + numTables + numInserts + numOthers);

        assertThat(records).isNotNull();
        records.forEach(this::validate);

        List<SourceRecord> changes = records.recordsForTopic(DATABASE.topicForTable("dbz_1194_datetime_key_test"));
        assertThat(changes).hasSize(1);

        assertKey(changes);

        try (Connection conn = getTestDatabaseConnection(DATABASE.getDatabaseName()).connection()) {
            conn.createStatement().execute("SET sql_mode='';");
            conn.createStatement().execute("INSERT INTO dbz_1194_datetime_key_test VALUES (default, '0000-00-00 00:00:00', '0000-00-00', '00:00:00')");
        }
        records = consumeRecordsByTopic(1);

        assertThat(records).isNotNull();
        records.forEach(this::validate);

        changes = records.recordsForTopic(DATABASE.topicForTable("dbz_1194_datetime_key_test"));
        assertThat(changes).hasSize(1);

        assertKey(changes);

        stopConnector();
    }

    private void assertKey(List<SourceRecord> changes) {
        Struct key = (Struct) changes.get(0).key();
        assertThat(key.getInt64("dtval")).isZero();
        assertThat(key.getInt64("tval")).isZero();
        assertThat(key.getInt32("dval")).isZero();
    }
}
