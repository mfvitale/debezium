/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.debezium.pipeline.signal.actions.Log;
import io.debezium.pipeline.signal.actions.SignalAction;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.SourceInfoStructMaker;
import io.debezium.data.Envelope;
import io.debezium.junit.logging.LogInterceptor;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.TableId;

/**
 * @author Jiri Pechanec
 *
 */
public class DatabaseSignalChannelTest {

    /*@Test
    public void shouldExecuteLog() throws Exception {
        final DatabaseSignalChannel<TestPartition> databaseSignalChannel = new DatabaseSignalChannel<>(config());
        final LogInterceptor log = new LogInterceptor(Log.class);
        assertThat(databaseSignalChannel.process(new TestPartition(), "log1", "log", "{\"message\": \"signallog {}\"}")).isTrue();
        assertThat(log.containsMessage("signallog <none>")).isTrue();
    }

    @Test
    public void shouldIgnoreInvalidSignalType() throws Exception {
        final DatabaseSignalChannel<TestPartition> databaseSignalChannel = new DatabaseSignalChannel<>(config());
        assertThat(databaseSignalChannel.process(new TestPartition(), "log1", "log1", "{\"message\": \"signallog\"}")).isFalse();
    }*/

    /*@Test
    public void shouldIgnoreUnparseableData() throws Exception {

        final DatabaseSignalChannel databaseSignalChannel = new DatabaseSignalChannel();
        final Schema afterSchema = SchemaBuilder.struct().name("signal")
                .field("col1", Schema.OPTIONAL_STRING_SCHEMA)
                .field("col2", Schema.OPTIONAL_STRING_SCHEMA)
                .field("col3", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Envelope env = Envelope.defineSchema()
                .withName("someName")
                .withRecord(afterSchema)
                .withSource(SchemaBuilder.struct().name("source").build())
                .build();
        final Struct record = new Struct(afterSchema);
        record.put("col1", "log1");
        record.put("col2", "custom");
        record.put("col3", "{\"message: \"signallog\"}");
        databaseSignalChannel.process(env.create(record, null, null), config());

        assertThat(databaseSignalChannel.read()).hasSize(0);
    }*/

    /*@Test
    public void shouldRegisterAdditionalAction() throws Exception {
        final DatabaseSignalChannel<TestPartition> databaseSignalChannel = new DatabaseSignalChannel<>(config());

        final AtomicInteger called = new AtomicInteger();
        final SignalAction<TestPartition> testAction = signalPayload -> {
            called.set(signalPayload.data.getInteger("v"));
            return true;
        };
        databaseSignalChannel.registerSignalAction("custom", testAction);
        assertThat(databaseSignalChannel.process(new TestPartition(), "log1", "custom", "{\"v\": 5}")).isTrue();
        assertThat(called.intValue()).isEqualTo(5);
    }*/

    @Test
    public void shouldExecuteFromEnvelope() throws Exception {
        final DatabaseSignalChannel databaseSignalChannel = new DatabaseSignalChannel();
        final Schema afterSchema = SchemaBuilder.struct().name("signal")
                .field("col1", Schema.OPTIONAL_STRING_SCHEMA)
                .field("col2", Schema.OPTIONAL_STRING_SCHEMA)
                .field("col3", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Envelope env = Envelope.defineSchema()
                .withName("someName")
                .withRecord(afterSchema)
                .withSource(SchemaBuilder.struct().name("source").build())
                .build();
        final Struct record = new Struct(afterSchema);
        record.put("col1", "log1");
        record.put("col2", "custom");
        record.put("col3", "{\"v\": 5}");

        databaseSignalChannel.process(env.create(record, null, null), config());
        List<SignalRecord> signalRecords = databaseSignalChannel.read();
        assertThat(signalRecords).hasSize(1);
        assertThat(signalRecords.get(0).getData()).isEqualTo("{\"v\": 5}");
    }

    @Test
    public void shouldIgnoreInvalidEnvelope() throws Exception {
        final DatabaseSignalChannel databaseSignalChannel = new DatabaseSignalChannel();
        final Schema afterSchema = SchemaBuilder.struct().name("signal")
                .field("col1", Schema.OPTIONAL_STRING_SCHEMA)
                .field("col2", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Envelope env = Envelope.defineSchema()
                .withName("someName")
                .withRecord(afterSchema)
                .withSource(SchemaBuilder.struct().name("source").build())
                .build();
        final Struct record = new Struct(afterSchema);
        record.put("col1", "log1");
        record.put("col2", "custom");

        databaseSignalChannel.process(env.create(record, null, null), config());
        assertThat(databaseSignalChannel.read()).hasSize(0);

        databaseSignalChannel.process( record, config());
        assertThat(databaseSignalChannel.read()).hasSize(0);
    }

    protected CommonConnectorConfig config() {
        return new CommonConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.SIGNAL_DATA_COLLECTION, "debezium.signal")
                .with(CommonConnectorConfig.TOPIC_PREFIX, "core")
                .build(), 0) {
            @Override
            protected SourceInfoStructMaker<?> getSourceInfoStructMaker(Version version) {
                return null;
            }

            @Override
            public String getContextName() {
                return null;
            }

            @Override
            public String getConnectorName() {
                return null;
            }
        };
    }

}
