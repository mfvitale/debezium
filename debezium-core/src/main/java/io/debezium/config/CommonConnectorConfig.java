/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.bean.DefaultBeanRegistry;
import io.debezium.bean.spi.BeanRegistry;
import io.debezium.config.Field.ValidationOutput;
import io.debezium.connector.AbstractSourceInfo;
import io.debezium.connector.SourceInfoStructMaker;
import io.debezium.data.Envelope;
import io.debezium.data.Envelope.Operation;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.heartbeat.HeartbeatConnectionProvider;
import io.debezium.heartbeat.HeartbeatErrorHandler;
import io.debezium.heartbeat.HeartbeatImpl;
import io.debezium.openlineage.OpenLineageConfig;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.notification.channels.SinkNotificationChannel;
import io.debezium.pipeline.txmetadata.DefaultTransactionMetadataFactory;
import io.debezium.pipeline.txmetadata.spi.TransactionMetadataFactory;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.schema.SchemaTopicNamingStrategy;
import io.debezium.service.DefaultServiceRegistry;
import io.debezium.service.spi.ServiceRegistry;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.spi.topic.TopicNamingStrategy;
import io.debezium.util.Strings;

/**
 * Configuration options common to all Debezium connectors.
 *
 * @author Gunnar Morling
 */
public abstract class CommonConnectorConfig {
    public static final Pattern TOPIC_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");
    public static final String MULTI_PARTITION_MODE = "multi.partition.mode";
    public static final String SNAPSHOT_MODE_PROPERTY_NAME = "snapshot.mode";
    public static final String SNAPSHOT_LOCKING_MODE_PROPERTY_NAME = "snapshot.locking.mode";

    private static final Logger LOGGER = LoggerFactory.getLogger(CommonConnectorConfig.class);
    protected SnapshotQueryMode snapshotQueryMode;
    protected String snapshotQueryModeCustomName;
    protected String snapshotLockingModeCustomName;
    protected final boolean snapshotModeConfigurationBasedSnapshotData;
    protected final boolean snapshotModeConfigurationBasedSnapshotSchema;
    protected final boolean snapshotModeConfigurationBasedStream;
    protected final boolean snapshotModeConfigurationBasedSnapshotOnSchemaError;
    protected final boolean snapshotModeConfigurationBasedSnapshotOnDataError;
    protected final boolean isLogPositionCheckEnabled;
    protected final boolean isAdvancedMetricsEnabled;
    private final boolean isExtendedHeadersEnabled;

    /**
     * The set of predefined versions e.g. for source struct maker version
     */
    public enum Version implements EnumeratedValue {
        V1("v1"),
        V2("v2");

        private final String value;

        Version(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static Version parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (Version option : Version.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static Version parse(String value, String defaultValue) {
            Version mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }

    }

    /**
     * The set of predefined modes for dealing with failures during event processing.
     */
    public enum EventProcessingFailureHandlingMode implements EnumeratedValue {

        /**
         * Problematic events will be skipped.
         */
        SKIP("skip"),

        /**
         * The position of problematic events will be logged and events will be skipped.
         */
        WARN("warn"),

        /**
         * An exception indicating the problematic events and their position is raised, causing the connector to be stopped.
         */
        FAIL("fail"),

        /**
         * Problematic events will be skipped - for transitional period only, scheduled to be removed.
         */
        IGNORE("ignore");

        private final String value;

        EventProcessingFailureHandlingMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static EventProcessingFailureHandlingMode parse(String value) {
            if (value == null) {
                return null;
            }

            value = value.trim();

            for (EventProcessingFailureHandlingMode option : EventProcessingFailureHandlingMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }

            return null;
        }

    }

    /**
     * The set of predefined BinaryHandlingMode options or aliases
     */
    public enum BinaryHandlingMode implements EnumeratedValue {

        /**
         * Represent binary values as byte array
         */
        BYTES("bytes", SchemaBuilder::bytes),

        /**
         * Represent binary values as base64-encoded string
         */
        BASE64("base64", SchemaBuilder::string),

        /**
         * Represent binary values as base64-url-safe-encoded string
         */
        BASE64_URL_SAFE("base64-url-safe", SchemaBuilder::string),

        /**
         * Represents binary values as hex-encoded (base16) string
         */
        HEX("hex", SchemaBuilder::string);

        private final String value;
        private final Supplier<SchemaBuilder> schema;

        BinaryHandlingMode(String value, Supplier<SchemaBuilder> schema) {
            this.value = value;
            this.schema = schema;
        }

        @Override
        public String getValue() {
            return value;
        }

        public SchemaBuilder getSchema() {
            return schema.get();
        }

        /**
         * Determine if the supplied values is one of the predefined options
         *
         * @param value the configuration property value ; may not be null
         * @return the matching option, or null if the match is not found
         */
        public static BinaryHandlingMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (BinaryHandlingMode option : BinaryHandlingMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied values is one of the predefined options
         *
         * @param value the configuration property value ; may not be null
         * @param defaultValue the default value ; may be null
         * @return the matching option or null if the match is not found and non-null default is invalid
         */
        public static BinaryHandlingMode parse(String value, String defaultValue) {
            BinaryHandlingMode mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }

    }

    /**
     * The set of predefined SchemaNameAdjustmentMode options
     */
    public enum SchemaNameAdjustmentMode implements EnumeratedValue {

        /**
         * Do not adjust names
         */
        NONE("none"),

        /**
         * Adjust names for compatibility with Avro
         */
        AVRO("avro"),

        /**
         * Adjust names for compatibility with Avro, replace invalid character to corresponding unicode
         */
        AVRO_UNICODE("avro_unicode");

        private final String value;

        SchemaNameAdjustmentMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        public SchemaNameAdjuster createAdjuster() {
            switch (this) {
                case AVRO:
                    return SchemaNameAdjuster.AVRO;
                case AVRO_UNICODE:
                    return SchemaNameAdjuster.AVRO_UNICODE;
                default:
                    return SchemaNameAdjuster.NO_OP;
            }
        }

        /**
         * Determine if the supplied values is one of the predefined options
         *
         * @param value the configuration property value ; may not be null
         * @return the matching option, or null if the match is not found
         */
        public static SchemaNameAdjustmentMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (SchemaNameAdjustmentMode option : SchemaNameAdjustmentMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

    }

    /**
     * The set of predefined FieldNameAdjustmentMode options
     */
    public enum FieldNameAdjustmentMode implements EnumeratedValue {

        /**
         * Do not adjust names
         */
        NONE("none"),

        /**
         * Adjust names for compatibility with Avro
         */
        AVRO("avro"),

        /**
         * Adjust names for compatibility with Avro, replace invalid character to corresponding unicode
         */
        AVRO_UNICODE("avro_unicode");

        private final String value;

        FieldNameAdjustmentMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        public SchemaNameAdjuster createAdjuster() {
            switch (this) {
                case AVRO:
                    return SchemaNameAdjuster.AVRO_FIELD_NAMER;
                case AVRO_UNICODE:
                    return SchemaNameAdjuster.AVRO_UNICODE_FIELD_NAMER;
                default:
                    return SchemaNameAdjuster.NO_OP;
            }
        }

        /**
         * Determine if the supplied values is one of the predefined options
         *
         * @param value the configuration property value ; may not be null
         * @return the matching option, or null if the match is not found
         */
        public static FieldNameAdjustmentMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (FieldNameAdjustmentMode option : FieldNameAdjustmentMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

    }

    /**
     * The set of predefined incremental snapshot watermarking strategies
     */
    public enum WatermarkStrategy implements EnumeratedValue {
        INSERT_INSERT("INSERT_INSERT"),
        INSERT_DELETE("INSERT_DELETE");

        private final String value;

        WatermarkStrategy(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static WatermarkStrategy parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (WatermarkStrategy option : WatermarkStrategy.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static WatermarkStrategy parse(String value, String defaultValue) {
            WatermarkStrategy mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }

    }

    public enum EventConvertingFailureHandlingMode implements EnumeratedValue {
        /**
         * Problematic events will be skipped.
         */
        SKIP("skip"),

        /**
         * The position of problematic events will be logged and events will be skipped.
         */
        WARN("warn"),

        /**
         * An exception indicating the problematic events and their position is raised, causing the connector to be stopped.
         */
        FAIL("fail");

        private final String value;

        EventConvertingFailureHandlingMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static EventConvertingFailureHandlingMode parse(String value) {
            if (value == null) {
                return null;
            }

            value = value.trim();

            for (EventConvertingFailureHandlingMode option : EventConvertingFailureHandlingMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }

            return null;
        }

    }

    public enum SnapshotQueryMode implements EnumeratedValue {
        /**
         * This mode will do a select based on {@code column.include.list} and {@code column.exclude.list} configurations.
         */
        SELECT_ALL("select_all"),

        CUSTOM("custom");

        private final String value;

        SnapshotQueryMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be {@code null}
         * @return the matching option, or null if no match is found
         */
        public static SnapshotQueryMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (SnapshotQueryMode option : SnapshotQueryMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be {@code null}
         * @param defaultValue the default value; may be {@code null}
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static SnapshotQueryMode parse(String value, String defaultValue) {
            SnapshotQueryMode mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }

    }

    private static final String CONFLUENT_AVRO_CONVERTER = "io.confluent.connect.avro.AvroConverter";
    private static final String APICURIO_AVRO_CONVERTER = "io.apicurio.registry.utils.converter.AvroConverter";

    // This should be less than the value of worker config task.shutdown.graceful.timeout.ms. It
    // defaults to 5 seconds, hence setting it to 4 seconds.
    public static final Duration DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(4);
    public static final int DEFAULT_MAX_QUEUE_SIZE = 8192;
    public static final int DEFAULT_MAX_BATCH_SIZE = 2048;
    public static final int DEFAULT_QUERY_FETCH_SIZE = 0;
    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 500;
    public static final String DATABASE_CONFIG_PREFIX = ConfigurationNames.DATABASE_CONFIG_PREFIX;
    public static final String DRIVER_CONFIG_PREFIX = "driver.";
    public static final long DEFAULT_RETRIABLE_RESTART_WAIT = 10000L;
    public static final long DEFAULT_MAX_QUEUE_SIZE_IN_BYTES = 0; // In case we don't want to pass max.queue.size.in.bytes;
    public static final String NOTIFICATION_CONFIGURATION_FIELD_PREFIX_STRING = "notification.";
    public static final long DEFAULT_CONNECTION_VALIDATION_TIMEOUT_MS = 60000L; // 60 seconds default timeout

    public static final int DEFAULT_MAX_RETRIES = ErrorHandler.RETRIES_UNLIMITED;
    public static final String ERRORS_MAX_RETRIES = "errors.max.retries";
    private final int maxRetriesOnError;

    public static final Field TOPIC_PREFIX = Field.create(ConfigurationNames.TOPIC_PREFIX_PROPERTY_NAME)
            .withDisplayName("Topic prefix")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTION, 0))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withValidation(CommonConnectorConfig::validateTopicName)
            .required()
            .withDescription("Topic prefix that identifies and provides a namespace for the particular database " +
                    "server/cluster is capturing changes. The topic prefix should be unique across all other connectors, " +
                    "since it is used as a prefix for all Kafka topic names that receive events emitted by this connector. " +
                    "Only alphanumeric characters, hyphens, dots and underscores must be accepted.");

    public static final Field RETRIABLE_RESTART_WAIT = Field.create("retriable.restart.connector.wait.ms")
            .withDisplayName("Retriable restart wait (ms)")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 18))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDefault(DEFAULT_RETRIABLE_RESTART_WAIT)
            .withDescription(
                    "Time to wait before restarting connector after retriable exception occurs. Defaults to " + DEFAULT_RETRIABLE_RESTART_WAIT + "ms.")
            .withValidation(Field::isPositiveLong);

    public static final Field TOMBSTONES_ON_DELETE = Field.create("tombstones.on.delete")
            .withDisplayName("Change the behaviour of Debezium with regards to delete operations")
            .withType(Type.BOOLEAN)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR, 1))
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDefault(true)
            .withValidation(Field::isBoolean)
            .withDescription("Whether delete operations should be represented by a delete event and a subsequent" +
                    " tombstone event (true) or only by a delete event (false). Emitting the tombstone event (the" +
                    " default behavior) allows Kafka to completely delete all events pertaining to the given key once" +
                    " the source record got deleted.");

    public static final Field MAX_QUEUE_SIZE = Field.create("max.queue.size")
            .withDisplayName("Change event buffer size")
            .withType(Type.INT)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 15))
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription("Maximum size of the queue for change events read from the database log but not yet recorded or forwarded. Defaults to "
                    + DEFAULT_MAX_QUEUE_SIZE + ", and should always be larger than the maximum batch size.")
            .withDefault(DEFAULT_MAX_QUEUE_SIZE)
            .withValidation(CommonConnectorConfig::validateMaxQueueSize);

    public static final Field MAX_BATCH_SIZE = Field.create("max.batch.size")
            .withDisplayName("Change event batch size")
            .withType(Type.INT)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 14))
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription("Maximum size of each batch of source records. Defaults to " + DEFAULT_MAX_BATCH_SIZE + ".")
            .withDefault(DEFAULT_MAX_BATCH_SIZE)
            .withValidation(Field::isPositiveInteger);

    public static final Field POLL_INTERVAL_MS = Field.create("poll.interval.ms")
            .withDisplayName("Poll interval (ms)")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 17))
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription("Time to wait for new change events to appear after receiving no events, given in milliseconds. Defaults to 500 ms.")
            .withDefault(DEFAULT_POLL_INTERVAL_MILLIS)
            .withValidation(Field::isPositiveInteger);

    public static final Field MAX_QUEUE_SIZE_IN_BYTES = Field.create("max.queue.size.in.bytes")
            .withDisplayName("Change event buffer size in bytes")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 16))
            .withWidth(Width.LONG)
            .withImportance(Importance.MEDIUM)
            .withDescription("Maximum size of the queue in bytes for change events read from the database log but not yet recorded or forwarded. Defaults to "
                    + DEFAULT_MAX_QUEUE_SIZE_IN_BYTES + ". Mean the feature is not enabled")
            .withDefault(DEFAULT_MAX_QUEUE_SIZE_IN_BYTES)
            .withValidation(Field::isNonNegativeLong);

    public static final Field SNAPSHOT_DELAY_MS = Field.create("snapshot.delay.ms")
            .withDisplayName("Snapshot Delay (milliseconds)")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 5))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("A delay period before a snapshot will begin, given in milliseconds. Defaults to 0 ms.")
            .withDefault(0L)
            .withValidation(Field::isNonNegativeLong);

    public static final Field SNAPSHOT_FETCH_SIZE = Field.create("snapshot.fetch.size")
            .withDisplayName("Snapshot fetch size")
            .withType(Type.INT)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 3))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("The maximum number of records that should be loaded into memory while performing a snapshot.")
            .withValidation(Field::isNonNegativeInteger);

    public static final Field INCREMENTAL_SNAPSHOT_CHUNK_SIZE = Field.create("incremental.snapshot.chunk.size")
            .withDisplayName("Incremental snapshot chunk size")
            .withType(Type.INT)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("The maximum size of chunk (number of documents/rows) for incremental snapshotting")
            .withDefault(1024)
            .withValidation(Field::isNonNegativeInteger);

    public static final Field INCREMENTAL_SNAPSHOT_ALLOW_SCHEMA_CHANGES = Field.create("incremental.snapshot.allow.schema.changes")
            .withDisplayName("Allow schema changes during incremental snapshot if supported.")
            .withType(Type.BOOLEAN)
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDescription("Detect schema change during an incremental snapshot and re-select a current chunk to avoid locking DDLs. " +
                    "Note that changes to a primary key are not supported and can cause incorrect results if performed during an incremental snapshot. " +
                    "Another limitation is that if a schema change affects only columns' default values, " +
                    "then the change won't be detected until the DDL is processed from the binlog stream. " +
                    "This doesn't affect the snapshot events' values, but the schema of snapshot events may have outdated defaults.")
            .withDefault(Boolean.FALSE);

    public static final Field SNAPSHOT_MODE_TABLES = Field.create("snapshot.include.collection.list")
            .withDisplayName("Snapshot mode include data collection")
            .withType(Type.LIST)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 2))
            .withWidth(Width.LONG)
            .withImportance(Importance.MEDIUM)
            .withValidation(Field::isListOfRegex)
            .withDescription(
                    "This setting must be set to specify a list of tables/collections whose snapshot must be taken on creating or restarting the connector.");

    public static final Field PROVIDE_TRANSACTION_METADATA = Field.create("provide.transaction.metadata")
            .withDisplayName("Store transaction metadata information in a dedicated topic.")
            .withType(Type.BOOLEAN)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 17))
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDescription("Enables transaction metadata extraction together with event counting")
            .withDefault(Boolean.FALSE);

    public static final Field TRANSACTION_METADATA_FACTORY = Field.create("transaction.metadata.factory")
            .withDisplayName("Factory class to create transaction context & transaction struct maker classes")
            .withType(Type.CLASS)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDefault(DefaultTransactionMetadataFactory.class.getName())
            .withDescription(
                    "Class to make transaction context & transaction struct/schemas");

    public static final Field EVENT_PROCESSING_FAILURE_HANDLING_MODE = Field.create("event.processing.failure.handling.mode")
            .withDisplayName("Event deserialization failure handling")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 12))
            .withEnum(EventProcessingFailureHandlingMode.class, EventProcessingFailureHandlingMode.FAIL)
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription("Specify how failures during processing of events (i.e. when encountering a corrupted event) should be handled, including: "
                    + "'fail' (the default) an exception indicating the problematic event and its position is raised, causing the connector to be stopped; "
                    + "'warn' the problematic event and its position will be logged and the event will be skipped; "
                    + "'ignore' the problematic event will be skipped.");

    public static final Field CUSTOM_CONVERTERS = Field.create("converters")
            .withDisplayName("List of prefixes defining custom values converters.")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 10))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Optional list of custom converters that would be used instead of default ones. "
                    + "The converters are defined using '<converter.prefix>.type' config option and configured using options '<converter.prefix>.<option>'");

    public static final Field CUSTOM_POST_PROCESSORS = Field.create("post.processors")
            .withDisplayName("List of change event post processors.")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 998))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Optional list of post processors. "
                    + "The processors are defined using '<post.processor.prefix>.type' config option and configured using options '<post.processor.prefix.<option>'");

    public static final Field SKIPPED_OPERATIONS = Field.create("skipped.operations")
            .withDisplayName("Skipped Operations")
            .withType(Type.LIST)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 11))
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withValidation(CommonConnectorConfig::validateSkippedOperation)
            .withDefault("t")
            .withDescription(
                    "The comma-separated list of operations to skip during streaming, defined as: 'c' for inserts/create; 'u' for updates; 'd' for deletes, 't' for truncates, and 'none' to indicate nothing skipped. "
                            + "By default, only truncate operations will be skipped.");

    /**
     *  Specifies whether to skip messages containing no updates in included columns
     */
    public static final Field SKIP_MESSAGES_WITHOUT_CHANGE = Field.create("skip.messages.without.change")
            .withDisplayName("Enable skipping messages without change")
            .withType(Type.BOOLEAN)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTION_ADVANCED, 0))
            .withDefault(false)
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription(("Enable to skip publishing messages when there is no change in included columns."
                    + "This would essentially filter messages to be sent when there is no change in columns included as per column.include.list/column.exclude.list."
                    + "For Postgres - this would require REPLICA IDENTITY of table to be FULL."))
            .withValidation(Field::isBoolean);

    public static final Field BINARY_HANDLING_MODE = Field.create("binary.handling.mode")
            .withDisplayName("Binary Handling")
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR, 3))
            .withEnum(BinaryHandlingMode.class, BinaryHandlingMode.BYTES)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Specify how binary (blob, binary, etc.) columns should be represented in change events, including: "
                    + "'bytes' represents binary data as byte array (default); "
                    + "'base64' represents binary data as base64-encoded string; "
                    + "'base64-url-safe' represents binary data as base64-url-safe-encoded string; "
                    + "'hex' represents binary data as hex-encoded (base16) string");

    public static final Field SCHEMA_NAME_ADJUSTMENT_MODE = Field.create("schema.name.adjustment.mode")
            .withDisplayName("Schema Name Adjustment")
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR, 7))
            .withEnum(SchemaNameAdjustmentMode.class, SchemaNameAdjustmentMode.NONE)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Specify how schema names should be adjusted for compatibility with the message converter used by the connector, including: "
                    + "'avro' replaces the characters that cannot be used in the Avro type name with underscore; "
                    + "'avro_unicode' replaces the underscore or characters that cannot be used in the Avro type name with corresponding unicode like _uxxxx. Note: _ is an escape sequence like backslash in Java;"
                    + "'none' does not apply any adjustment (default)");

    public static final Field FIELD_NAME_ADJUSTMENT_MODE = Field.create("field.name.adjustment.mode")
            .withDisplayName("Field Name Adjustment")
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR, 7))
            .withEnum(FieldNameAdjustmentMode.class, FieldNameAdjustmentMode.NONE)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Specify how field names should be adjusted for compatibility with the message converter used by the connector, including: "
                    + "'avro' replaces the characters that cannot be used in the Avro type name with underscore; "
                    + "'avro_unicode' replaces the underscore or characters that cannot be used in the Avro type name with corresponding unicode like _uxxxx. Note: _ is an escape sequence like backslash in Java;"
                    + "'none' does not apply any adjustment (default)");

    public static final Field QUERY_FETCH_SIZE = Field.create("query.fetch.size")
            .withDisplayName("Query fetch size")
            .withType(Type.INT)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 13))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("The maximum number of records that should be loaded into memory while streaming. A value of '0' uses the default JDBC fetch size.")
            .withValidation(Field::isNonNegativeInteger)
            .withDefault(DEFAULT_QUERY_FETCH_SIZE);

    public static final Field SNAPSHOT_MAX_THREADS = Field.create("snapshot.max.threads")
            .withDisplayName("Snapshot maximum threads")
            .withType(Type.INT)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 7))
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDefault(1)
            .withValidation(Field::isPositiveInteger)
            .withDescription("The maximum number of threads used to perform the snapshot. Defaults to 1.");

    public static final Field SIGNAL_DATA_COLLECTION = Field.create("signal.data.collection")
            .withDisplayName("Signaling data collection")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 20))
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("The name of the data collection that is used to send signals/commands to Debezium. Signaling is disabled when not set.");

    public static final Field SIGNAL_POLL_INTERVAL_MS = Field.create("signal.poll.interval.ms")
            .withDisplayName("Signal processor poll interval")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 21))
            .withType(Type.LONG)
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDefault(5000L)
            .withValidation(Field::isPositiveInteger)
            .withDescription("Interval for looking for new signals in registered channels, given in milliseconds. Defaults to 5 seconds.");

    public static final Field SIGNAL_ENABLED_CHANNELS = Field.create("signal.enabled.channels")
            .withDisplayName("Enabled channels names")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 22))
            .withType(Type.LIST)
            .withWidth(Width.LONG)
            .withImportance(Importance.MEDIUM)
            .withDefault("source")
            .withDescription("List of channels names that are enabled. Source channel is enabled by default");

    public static final Field TOPIC_NAMING_STRATEGY = Field.create("topic.naming.strategy")
            .withDisplayName("Topic naming strategy class")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 23))
            .withType(Type.CLASS)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("The name of the TopicNamingStrategy class that should be used to determine the topic name " +
                    "for data change, schema change, transaction, heartbeat event etc.")
            .withDefault(SchemaTopicNamingStrategy.class.getName());

    public static final Field CUSTOM_RETRIABLE_EXCEPTION = Field.createInternal("custom.retriable.exception")
            .withDisplayName("Regular expression to match the exception message.")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 999))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Provide a temporary workaround for an error that should be retriable."
                    + " If set a stacktrace of non-retriable exception is traversed and messages are"
                    + " matched against this regular expression. If matched the error is changed to retriable.")
            .withDefault(false);

    public static final Field NOTIFICATION_ENABLED_CHANNELS = Field.create(NOTIFICATION_CONFIGURATION_FIELD_PREFIX_STRING + "enabled.channels")
            .withDisplayName("Enabled notification channels names")
            .withType(Type.LIST)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(Importance.MEDIUM)
            .withDescription("List of notification channels names that are enabled.");

    public static final Field SOURCE_INFO_STRUCT_MAKER = Field.create("sourceinfo.struct.maker")
            .withDisplayName("Source info struct maker class")
            .withType(Type.CLASS)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("The name of the SourceInfoStructMaker class that returns SourceInfo schema and struct.");

    public static final Field MAX_RETRIES_ON_ERROR = Field.create(ERRORS_MAX_RETRIES)
            .withDisplayName("The maximum number of retries")
            .withType(Type.INT)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 24))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDefault(DEFAULT_MAX_RETRIES)
            .withValidation(Field::isInteger)
            .withDescription(
                    "The maximum number of retries on connection errors before failing (-1 = no limit, 0 = disabled, > 0 = num of retries).");

    public static final Field CUSTOM_METRIC_TAGS = Field.create("custom.metric.tags")
            .withDisplayName("Customize metric tags")
            .withType(Type.LIST)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 25))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withValidation(Field::isListOfMap)
            .withDescription("The custom metric tags will accept key-value pairs to customize the MBean object name "
                    + "which should be appended the end of regular name, each key would represent a tag for the MBean object name, "
                    + "and the corresponding value would be the value of that tag the key is. For example: k1=v1,k2=v2");

    public static final Field INCREMENTAL_SNAPSHOT_WATERMARKING_STRATEGY = Field.create("incremental.snapshot.watermarking.strategy")
            .withDisplayName("Incremental snapshot watermarking strategy")
            .withEnum(WatermarkStrategy.class, WatermarkStrategy.INSERT_INSERT)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("Specify the strategy used for watermarking during an incremental snapshot: "
                    + "'insert_insert' both open and close signal is written into signal data collection (default); "
                    + "'insert_delete' only open signal is written on signal data collection, the close will delete the relative open signal;");

    public static final Field SNAPSHOT_MODE_CUSTOM_NAME = Field.create("snapshot.mode.custom.name")
            .withDisplayName("Snapshot Mode Custom Name")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 12))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withValidation((config, field, output) -> {
                if ("custom".equalsIgnoreCase(config.getString("snapshot.mode")) && config.getString(field, "").isEmpty()) {
                    output.accept(field, "", "snapshot.mode.custom.name cannot be empty when snapshot.mode 'custom' is defined");
                    return 1;
                }
                return 0;
            })
            .withDescription(
                    "When 'snapshot.mode' is set as custom, this setting must be set to specify a the name of the custom implementation provided in the 'name()' method. "
                            + "The implementations must implement the 'Snapshotter' interface and is called on each app boot to determine whether to do a snapshot.");

    public static final Field EVENT_CONVERTING_FAILURE_HANDLING_MODE = Field.create("event.converting.failure.handling.mode")
            .withDisplayName("Event converting failure handling mode")
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 26))
            .withEnum(EventConvertingFailureHandlingMode.class, EventConvertingFailureHandlingMode.WARN)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("Specify how failures during converting of event should be handled, including: "
                    + "'fail' throw an exception that the column of event conversion is failed with unmatched schema type, causing the connector to be stopped. it could need schema recovery to covert successfully; "
                    + "'warn' (the default) the value of column of event that conversion failed will be null and be logged with warn level; "
                    + "'skip' the value of column of event that conversion failed will be null and be logged with debug level.");

    public static final Field STREAMING_DELAY_MS = Field.create("streaming.delay.ms")
            .withDisplayName("Streaming Delay (milliseconds)")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_ADVANCED, 27))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.LOW)
            .withDescription("A delay period after the snapshot is completed and the streaming begins, given in milliseconds. Defaults to 0 ms.")
            .withDefault(0L)
            .withValidation(Field::isNonNegativeLong);

    public static final Field SNAPSHOT_LOCKING_MODE_CUSTOM_NAME = Field.create("snapshot.locking.mode.custom.name")
            .withDisplayName("Snapshot Locking Mode Custom Name")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 14))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withValidation((config, field, output) -> {
                if ("custom".equalsIgnoreCase(config.getString("snapshot.locking.mode")) && config.getString(field, "").isEmpty()) {
                    output.accept(field, "", "snapshot.locking.mode.custom.name cannot be empty when snapshot.locking.mode 'custom' is defined");
                    return 1;
                }
                return 0;
            })
            .withDescription(
                    "When 'snapshot.locking.mode' is set as custom, this setting must be set to specify a the name of the custom implementation provided in the 'name()' method. "
                            + "The implementations must implement the 'SnapshotterLocking' interface and is called to determine how to lock tables during schema snapshot.");

    public static final Field SNAPSHOT_QUERY_MODE = Field.create("snapshot.query.mode")
            .withDisplayName("Snapshot query mode")
            .withEnum(SnapshotQueryMode.class, SnapshotQueryMode.SELECT_ALL)
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 15))
            .withDescription("Controls query used during the snapshot");

    public static final Field SNAPSHOT_QUERY_MODE_CUSTOM_NAME = Field.create("snapshot.query.mode.custom.name")
            .withDisplayName("Snapshot Query Mode Custom Name")
            .withType(Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 16))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withValidation((config, field, output) -> {
                if ("custom".equalsIgnoreCase(config.getString(SNAPSHOT_QUERY_MODE)) && config.getString(field, "").isEmpty()) {
                    output.accept(field, "", "snapshot.query.mode.custom.name cannot be empty when snapshot.query.mode 'custom' is defined");
                    return 1;
                }
                return 0;
            })
            .withDescription(
                    "When 'snapshot.query.mode' is set as custom, this setting must be set to specify a the name of the custom implementation provided in the 'name()' method. "
                            + "The implementations must implement the 'SnapshotterQuery' interface and is called to determine how to build queries during snapshot.");

    public static final Field SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_DATA = Field.create("snapshot.mode.configuration.based.snapshot.data")
            .withDisplayName("Snapshot mode property based snapshot data")
            .withType(Type.BOOLEAN)
            .withDefault(false)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 17))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription(
                    "When 'snapshot.mode' is set as configuration_based, this setting permits to specify whenever the data should be snapshotted or not.");

    public static final Field SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_SCHEMA = Field.create("snapshot.mode.configuration.based.snapshot.schema")
            .withDisplayName("Snapshot mode property based snapshot schema")
            .withType(Type.BOOLEAN)
            .withDefault(false)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 18))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription(
                    "When 'snapshot.mode' is set as configuration_based, this setting permits to specify whenever the schema should be snapshotted or not.");

    public static final Field SNAPSHOT_MODE_CONFIGURATION_BASED_START_STREAM = Field.create("snapshot.mode.configuration.based.start.stream")
            .withDisplayName("Snapshot mode property based start stream")
            .withType(Type.BOOLEAN)
            .withDefault(false)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 19))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription(
                    "When 'snapshot.mode' is set as configuration_based, this setting permits to specify whenever the stream should start or not after snapshot.");

    public static final Field SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_ON_SCHEMA_ERROR = Field.create("snapshot.mode.configuration.based.snapshot.on.schema.error")
            .withDisplayName("Snapshot mode property based snapshot on schema error")
            .withType(Type.BOOLEAN)
            .withDefault(false)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 20))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription(
                    "When 'snapshot.mode' is set as configuration_based, this setting permits to specify whenever the schema should be snapshotted or not in case of error.");

    public static final Field SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_ON_DATA_ERROR = Field.create("snapshot.mode.configuration.based.snapshot.on.data.error")
            .withDisplayName("Snapshot mode property based snapshot on data error")
            .withType(Type.BOOLEAN)
            .withDefault(false)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 21))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription(
                    "When 'snapshot.mode' is set as configuration_based, this setting permits to specify whenever the data should be snapshotted or not in case of error.");

    public static final Field LOG_POSITION_CHECK_ENABLED = Field.createInternal("log.position.check.enable")
            .withDisplayName("Enable/Disable log position check")
            .withType(Type.BOOLEAN)
            .withDefault(true)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 30))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription("When enabled the connector checks if the position stored in the offset is still available in the log");

    public static final Field ADVANCED_METRICS_ENABLE = Field.createInternal("advanced.metrics.enable")
            .withDisplayName("Enable/Disable advance metrics")
            .withType(Type.BOOLEAN)
            .withDefault(false)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 31))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .optional()
            .withDescription("When enabled the connector will emit advanced streaming metrics");

    public static final Field CONNECTION_VALIDATION_TIMEOUT_MS = Field.create("connection.validation.timeout.ms")
            .withDisplayName("Connection validation timeout (ms)")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTION, 13))
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDefault(DEFAULT_CONNECTION_VALIDATION_TIMEOUT_MS)
            .withDescription("The maximum time in milliseconds to wait for connection validation to complete. Defaults to 60 seconds.")
            .withValidation(Field::isPositiveLong);

    public static final Field EXECUTOR_SHUTDOWN_TIMEOUT_MS = Field.create("executor.shutdown.timeout.ms")
            .withDisplayName("Executor shutdown timeout (ms)")
            .withType(Type.LONG)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 19))
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDefault(DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT.toMillis())
            .withDescription("The maximum time in milliseconds to wait for task executor to shut down.")
            .withValidation(Field::isPositiveLong);

    /**
     * Configuration field to enable or disable OpenLineage integration.
     *
     * <p>When enabled, Debezium will emit data lineage metadata through the OpenLineage API,
     * providing visibility into data flows and transformations performed by the connector.</p>
     *
     * <p><strong>Configuration key:</strong> {@code openlineage.integration.enabled}<br>
     * <strong>Type:</strong> Boolean<br>
     * <strong>Default:</strong> {@code false}<br>
     * <strong>Importance:</strong> Low</p>
     */
    public static Field OPEN_LINEAGE_INTEGRATION_ENABLED = Field.create(OpenLineageConfig.OPEN_LINEAGE_INTEGRATION_ENABLED)
            .withDisplayName("Enables OpenLineage integration")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 40))
            .withType(ConfigDef.Type.BOOLEAN)
            .withWidth(ConfigDef.Width.SHORT)
            .withImportance(ConfigDef.Importance.LOW)
            .withDescription("Enable Debezium to emit data lineage metadata through OpenLineage API")
            .withDefault(false);

    /**
     * Configuration field specifying the path to the OpenLineage configuration file.
     *
     * <p>This file contains OpenLineage client configuration settings such as transport
     * configuration, backend settings, and other OpenLineage-specific options.</p>
     *
     * <p><strong>Configuration key:</strong> {@code openlineage.integration.config.file.path}<br>
     * <strong>Type:</strong> String<br>
     * <strong>Default:</strong> {@code ./openlineage.yml}<br>
     * <strong>Importance:</strong> Low</p>
     *
     * @see <a href="https://openlineage.io/docs/client/java/configuration">OpenLineage Configuration Documentation</a>
     */
    public static Field OPEN_LINEAGE_INTEGRATION_CONFIG_FILE_PATH = Field.create(OpenLineageConfig.OPEN_LINEAGE_INTEGRATION_CONFIG_FILE_PATH)
            .withDisplayName("Path to OpenLineage file configuration")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 41))
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withDefault("./openlineage.yml")
            .withDescription("Path to OpenLineage file configuration. See https://openlineage.io/docs/client/java/configuration");

    /**
     * Configuration field defining the namespace for the Debezium job in OpenLineage.
     *
     * <p>The namespace is used to organize and categorize jobs within the OpenLineage
     * metadata system. It helps in identifying and grouping related data pipeline jobs.</p>
     *
     * <p><strong>Configuration key:</strong> {@code openlineage.integration.job.namespace}<br>
     * <strong>Type:</strong> String<br>
     * <strong>Default:</strong> None<br>
     * <strong>Importance:</strong> Low</p>
     */
    public static Field OPEN_LINEAGE_INTEGRATION_JOB_NAMESPACE = Field.create(OpenLineageConfig.OPEN_LINEAGE_INTEGRATION_JOB_NAMESPACE)
            .withDisplayName("Namespace used for Debezium job")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 42))
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withDescription("The job's namespace emitted by Debezium");

    /**
     * Configuration field providing a human-readable description for the Debezium job.
     *
     * <p>This description appears in OpenLineage metadata and helps users understand
     * the purpose and function of the specific Debezium connector job.</p>
     *
     * <p><strong>Configuration key:</strong> {@code openlineage.integration.job.description}<br>
     * <strong>Type:</strong> String<br>
     * <strong>Default:</strong> {@code "Debezium change data capture job"}<br>
     * <strong>Importance:</strong> Low</p>
     */
    public static Field OPEN_LINEAGE_INTEGRATION_JOB_DESCRIPTION = Field.create(OpenLineageConfig.OPEN_LINEAGE_INTEGRATION_JOB_DESCRIPTION)
            .withDisplayName("Description used for Debezium job")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 43))
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withDescription("The job's description emitted by Debezium")
            .withDefault("Debezium change data capture job");

    /**
     * Configuration field for specifying tags associated with the Debezium job.
     *
     * <p>Tags provide additional metadata and categorization for the job in OpenLineage.
     * They are specified as a comma-separated list of key-value pairs.</p>
     *
     * <p><strong>Configuration key:</strong> {@code openlineage.integration.job.tags}<br>
     * <strong>Type:</strong> List<br>
     * <strong>Format:</strong> Comma-separated key-value pairs (e.g., {@code k1=v1,k2=v2})<br>
     * <strong>Default:</strong> None<br>
     * <strong>Importance:</strong> Low</p>
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * openlineage.integration.job.tags=environment=production,team=data-engineering
     * }</pre>
     */
    public static Field OPEN_LINEAGE_INTEGRATION_JOB_TAGS = Field.create(OpenLineageConfig.OPEN_LINEAGE_INTEGRATION_JOB_TAGS)
            .withDisplayName("Debezium job tags")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 44))
            .withType(ConfigDef.Type.LIST)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(Field::isListOfMap)
            .withDescription("The job's tags emitted by Debezium. A comma-separated list of key-value pairs.For example: k1=v1,k2=v2");

    /**
     * Configuration field for specifying the owners of the Debezium job.
     *
     * <p>Owners identify who is responsible for the job and can be used for contact
     * information, governance, and accountability within the OpenLineage metadata system.
     * They are specified as a comma-separated list of key-value pairs.</p>
     *
     * <p><strong>Configuration key:</strong> {@code openlineage.integration.job.owners}<br>
     * <strong>Type:</strong> List<br>
     * <strong>Format:</strong> Comma-separated key-value pairs (e.g., {@code k1=v1,k2=v2})<br>
     * <strong>Default:</strong> None<br>
     * <strong>Importance:</strong> Low</p>
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * openlineage.integration.job.owners=email=team@company.com,slack=data-team
     * }</pre>
     */
    public static Field OPEN_LINEAGE_INTEGRATION_JOB_OWNERS = Field.create(OpenLineageConfig.OPEN_LINEAGE_INTEGRATION_JOB_OWNERS)
            .withDisplayName("Debezium job owners")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 45))
            .withType(ConfigDef.Type.LIST)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(Field::isListOfMap)
            .withDescription("The job's owners emitted by Debezium. A comma-separated list of key-value pairs.For example: k1=v1,k2=v2");

    /**
     * Configuration field for enabling or disabling extended Debezium context headers.
     *
     * <p>This field controls whether Debezium includes additional context headers in CDC events
     * that provide essential metadata for tracking and identifying the source of change data
     * capture events in downstream processing systems.</p>
     *
     * <p><strong>Configuration Details:</strong></p>
     * <ul>
     *   <li><strong>Type:</strong> Boolean</li>
     *   <li><strong>Default:</strong> Not specified (implementation dependent)</li>
     *   <li><strong>Importance:</strong> Low</li>
     *   <li><strong>Group:</strong> Advanced (position 46)</li>
     *   <li><strong>Width:</strong> Short</li>
     * </ul>
     *
     * <p>When enabled, this configuration adds metadata headers that can be used by downstream
     * systems for:</p>
     * <ul>
     *   <li>Event source tracking and lineage</li>
     *   <li>Identifying the origin of CDC events</li>
     *   <li>Enhanced monitoring and debugging capabilities</li>
     * </ul>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * // Enable extended headers
     * config.put("extended.headers.enabled", true);
     *
     * // Disable extended headers (default behavior may vary)
     * config.put("extended.headers.enabled", false);
     * }</pre>
     *
     * @see ConfigurationNames#EXTENDED_HEADERS_ENABLED
     * @see Field.Group#ADVANCED
     * @since [3.2.1.Final, 3.3.0.Alpha1]
     */
    public static Field EXTENDED_HEADERS_ENABLED = Field.create(ConfigurationNames.EXTENDED_HEADERS_ENABLED)
            .withDisplayName("Enable/Disable extended headers")
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED, 46))
            .withType(Type.BOOLEAN)
            .withWidth(Width.SHORT)
            .withDefault(true)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(Field::isBoolean)
            .withDescription(
                    "Enable/Disable Debezium context headers that provides essential metadata for tracking and identifying the source of CDC events in downstream processing systems.");

    protected static final ConfigDefinition CONFIG_DEFINITION = ConfigDefinition.editor()
            .connector(
                    EVENT_PROCESSING_FAILURE_HANDLING_MODE,
                    MAX_BATCH_SIZE,
                    MAX_QUEUE_SIZE,
                    POLL_INTERVAL_MS,
                    MAX_QUEUE_SIZE_IN_BYTES,
                    PROVIDE_TRANSACTION_METADATA,
                    SKIPPED_OPERATIONS,
                    SNAPSHOT_DELAY_MS,
                    STREAMING_DELAY_MS,
                    SNAPSHOT_MODE_TABLES,
                    SNAPSHOT_FETCH_SIZE,
                    SNAPSHOT_MAX_THREADS,
                    SNAPSHOT_MODE_CUSTOM_NAME,
                    SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_DATA,
                    SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_SCHEMA,
                    SNAPSHOT_MODE_CONFIGURATION_BASED_START_STREAM,
                    SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_ON_SCHEMA_ERROR,
                    SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_ON_DATA_ERROR,
                    RETRIABLE_RESTART_WAIT,
                    QUERY_FETCH_SIZE,
                    MAX_RETRIES_ON_ERROR,
                    INCREMENTAL_SNAPSHOT_WATERMARKING_STRATEGY,
                    LOG_POSITION_CHECK_ENABLED,
                    ADVANCED_METRICS_ENABLE,
                    CONNECTION_VALIDATION_TIMEOUT_MS,
                    EXECUTOR_SHUTDOWN_TIMEOUT_MS,
                    OPEN_LINEAGE_INTEGRATION_ENABLED,
                    OPEN_LINEAGE_INTEGRATION_CONFIG_FILE_PATH,
                    OPEN_LINEAGE_INTEGRATION_JOB_NAMESPACE,
                    OPEN_LINEAGE_INTEGRATION_JOB_DESCRIPTION,
                    OPEN_LINEAGE_INTEGRATION_JOB_TAGS,
                    OPEN_LINEAGE_INTEGRATION_JOB_OWNERS,
                    EXTENDED_HEADERS_ENABLED)
            .events(
                    CUSTOM_CONVERTERS,
                    CUSTOM_POST_PROCESSORS,
                    TOMBSTONES_ON_DELETE,
                    Heartbeat.HEARTBEAT_INTERVAL,
                    Heartbeat.HEARTBEAT_TOPICS_PREFIX,
                    SIGNAL_DATA_COLLECTION,
                    SIGNAL_POLL_INTERVAL_MS,
                    SIGNAL_ENABLED_CHANNELS,
                    TOPIC_NAMING_STRATEGY,
                    NOTIFICATION_ENABLED_CHANNELS,
                    SinkNotificationChannel.NOTIFICATION_TOPIC,
                    TRANSACTION_METADATA_FACTORY,
                    CUSTOM_METRIC_TAGS)
            .create();

    private final Configuration config;
    private final boolean emitTombstoneOnDelete;
    private final int maxQueueSize;
    private final int maxBatchSize;
    private final long maxQueueSizeInBytes;
    private final Duration pollInterval;
    protected final String logicalName;
    private final String heartbeatTopicsPrefix;
    private final Duration heartbeatInterval;
    private final Duration snapshotDelay;
    private final Duration streamingDelay;
    private final Duration retriableRestartWait;
    private final int snapshotFetchSize;
    private final int incrementalSnapshotChunkSize;
    private final boolean incrementalSnapshotAllowSchemaChanges;
    private final int snapshotMaxThreads;

    private final String snapshotModeCustomName;
    private final Integer queryFetchSize;
    private final SourceInfoStructMaker<? extends AbstractSourceInfo> sourceInfoStructMaker;
    private final TransactionMetadataFactory transactionMetadataFactory;
    private final boolean shouldProvideTransactionMetadata;
    private final EventProcessingFailureHandlingMode eventProcessingFailureHandlingMode;
    private final BinaryHandlingMode binaryHandlingMode;
    private final SchemaNameAdjustmentMode schemaNameAdjustmentMode;
    private final FieldNameAdjustmentMode fieldNameAdjustmentMode;
    private final EventConvertingFailureHandlingMode eventConvertingFailureHandlingMode;
    private final String signalingDataCollection;
    private final TableId signalingDataCollectionId;

    private final Duration signalPollInterval;

    private final List<String> signalEnabledChannels;
    private final EnumSet<Operation> skippedOperations;
    private final String taskId;
    private final boolean skipMessagesWithoutChange;

    private final String notificationTopicName;
    private final List<String> enabledNotificationChannels;
    private final Map<String, String> customMetricTags;
    private WatermarkStrategy incrementalSnapshotWatermarkingStrategy;

    // Intentionally protected so that subclasses can access internal contracts
    protected final DefaultBeanRegistry beanRegistry;
    protected final DefaultServiceRegistry serviceRegistry;

    protected CommonConnectorConfig(Configuration config, int defaultSnapshotFetchSize) {
        this.beanRegistry = new DefaultBeanRegistry();
        this.serviceRegistry = new DefaultServiceRegistry(config, beanRegistry);
        this.config = config;
        this.emitTombstoneOnDelete = config.getBoolean(CommonConnectorConfig.TOMBSTONES_ON_DELETE);
        this.maxQueueSize = config.getInteger(MAX_QUEUE_SIZE);
        this.maxBatchSize = config.getInteger(MAX_BATCH_SIZE);
        this.pollInterval = config.getDuration(POLL_INTERVAL_MS, ChronoUnit.MILLIS);
        this.maxQueueSizeInBytes = config.getLong(MAX_QUEUE_SIZE_IN_BYTES);
        this.logicalName = config.getString(CommonConnectorConfig.TOPIC_PREFIX);
        this.heartbeatTopicsPrefix = config.getString(Heartbeat.HEARTBEAT_TOPICS_PREFIX);
        this.heartbeatInterval = config.getDuration(Heartbeat.HEARTBEAT_INTERVAL, ChronoUnit.MILLIS);
        this.snapshotDelay = Duration.ofMillis(config.getLong(SNAPSHOT_DELAY_MS));
        this.streamingDelay = Duration.ofMillis(config.getLong(STREAMING_DELAY_MS));
        this.retriableRestartWait = Duration.ofMillis(config.getLong(RETRIABLE_RESTART_WAIT));
        this.snapshotFetchSize = config.getInteger(SNAPSHOT_FETCH_SIZE, defaultSnapshotFetchSize);
        this.snapshotMaxThreads = config.getInteger(SNAPSHOT_MAX_THREADS);
        this.snapshotModeCustomName = config.getString(SNAPSHOT_MODE_CUSTOM_NAME);
        this.queryFetchSize = config.getInteger(QUERY_FETCH_SIZE);
        this.incrementalSnapshotChunkSize = config.getInteger(INCREMENTAL_SNAPSHOT_CHUNK_SIZE);
        this.incrementalSnapshotAllowSchemaChanges = config.getBoolean(INCREMENTAL_SNAPSHOT_ALLOW_SCHEMA_CHANGES);
        this.schemaNameAdjustmentMode = SchemaNameAdjustmentMode.parse(config.getString(SCHEMA_NAME_ADJUSTMENT_MODE));
        this.fieldNameAdjustmentMode = FieldNameAdjustmentMode.parse(config.getString(FIELD_NAME_ADJUSTMENT_MODE));
        this.eventConvertingFailureHandlingMode = EventConvertingFailureHandlingMode.parse(config.getString(EVENT_CONVERTING_FAILURE_HANDLING_MODE));
        this.sourceInfoStructMaker = getSourceInfoStructMaker(Version.V2);
        this.transactionMetadataFactory = getTransactionMetadataFactory();
        this.shouldProvideTransactionMetadata = config.getBoolean(PROVIDE_TRANSACTION_METADATA);
        this.eventProcessingFailureHandlingMode = EventProcessingFailureHandlingMode.parse(config.getString(EVENT_PROCESSING_FAILURE_HANDLING_MODE));
        this.binaryHandlingMode = BinaryHandlingMode.parse(config.getString(BINARY_HANDLING_MODE));
        this.signalingDataCollection = config.getString(SIGNAL_DATA_COLLECTION);
        this.signalPollInterval = Duration.ofMillis(config.getLong(SIGNAL_POLL_INTERVAL_MS));
        this.signalEnabledChannels = getSignalEnabledChannels(config);
        this.skippedOperations = determineSkippedOperations(config);
        this.taskId = config.getString(ConfigurationNames.TASK_ID_PROPERTY_NAME);
        this.notificationTopicName = config.getString(SinkNotificationChannel.NOTIFICATION_TOPIC);
        this.enabledNotificationChannels = config.getList(NOTIFICATION_ENABLED_CHANNELS);
        this.skipMessagesWithoutChange = config.getBoolean(SKIP_MESSAGES_WITHOUT_CHANGE);
        this.maxRetriesOnError = config.getInteger(MAX_RETRIES_ON_ERROR);
        this.customMetricTags = createCustomMetricTags(config);
        this.incrementalSnapshotWatermarkingStrategy = WatermarkStrategy.parse(config.getString(INCREMENTAL_SNAPSHOT_WATERMARKING_STRATEGY));
        this.snapshotLockingModeCustomName = config.getString(SNAPSHOT_LOCKING_MODE_CUSTOM_NAME, "");
        this.snapshotQueryMode = SnapshotQueryMode.parse(config.getString(SNAPSHOT_QUERY_MODE), SNAPSHOT_QUERY_MODE.defaultValueAsString());
        this.snapshotQueryModeCustomName = config.getString(SNAPSHOT_QUERY_MODE_CUSTOM_NAME, "");
        this.snapshotModeConfigurationBasedSnapshotData = config.getBoolean(SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_DATA);
        this.snapshotModeConfigurationBasedSnapshotSchema = config.getBoolean(SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_SCHEMA);
        this.snapshotModeConfigurationBasedStream = config.getBoolean(SNAPSHOT_MODE_CONFIGURATION_BASED_START_STREAM);
        this.snapshotModeConfigurationBasedSnapshotOnSchemaError = config.getBoolean(SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_ON_SCHEMA_ERROR);
        this.snapshotModeConfigurationBasedSnapshotOnDataError = config.getBoolean(SNAPSHOT_MODE_CONFIGURATION_BASED_SNAPSHOT_ON_DATA_ERROR);
        this.isLogPositionCheckEnabled = config.getBoolean(LOG_POSITION_CHECK_ENABLED);
        this.isAdvancedMetricsEnabled = config.getBoolean(ADVANCED_METRICS_ENABLE);
        this.isExtendedHeadersEnabled = config.getBoolean(EXTENDED_HEADERS_ENABLED);

        this.signalingDataCollectionId = !Strings.isNullOrBlank(this.signalingDataCollection)
                ? TableId.parse(this.signalingDataCollection)
                : null;
    }

    private static List<String> getSignalEnabledChannels(Configuration config) {

        if (config.hasKey(SIGNAL_ENABLED_CHANNELS)) {
            return config.getList(SIGNAL_ENABLED_CHANNELS);
        }
        return Arrays.stream(Objects.requireNonNull(SIGNAL_ENABLED_CHANNELS.defaultValueAsString()).split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private static EnumSet<Envelope.Operation> determineSkippedOperations(Configuration config) {
        String operations = config.getString(SKIPPED_OPERATIONS);

        if (operations != null) {
            if (operations.trim().equalsIgnoreCase("none")) {
                return EnumSet.noneOf(Envelope.Operation.class);
            }
            return EnumSet.copyOf(Arrays.stream(operations.split(","))
                    .map(String::trim)
                    .map(Operation::forCode)
                    .collect(Collectors.toSet()));
        }
        else {
            return EnumSet.noneOf(Envelope.Operation.class);
        }
    }

    /**
     * Provides access to the "raw" config instance. In most cases, access via typed getters for individual properties
     * on the connector config class should be preferred.
     * TODO this should be protected in the future to force proper facade methods based access / encapsulation
     */
    @Deprecated
    public Configuration getConfig() {
        return config;
    }

    public BeanRegistry getBeanRegistry() {
        return beanRegistry;
    }

    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public boolean isEmitTombstoneOnDelete() {
        return emitTombstoneOnDelete;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public long getMaxQueueSizeInBytes() {
        return maxQueueSizeInBytes;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public String getLogicalName() {
        return logicalName;
    }

    public abstract String getContextName();

    public abstract String getConnectorName();

    public abstract EnumeratedValue getSnapshotMode();

    public abstract Optional<? extends EnumeratedValue> getSnapshotLockingMode();

    public String getHeartbeatTopicsPrefix() {
        return heartbeatTopicsPrefix;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public Duration getSnapshotDelay() {
        return snapshotDelay;
    }

    public Duration getStreamingDelay() {
        return streamingDelay;
    }

    public int getSnapshotFetchSize() {
        return snapshotFetchSize;
    }

    public int getSnapshotMaxThreads() {
        return snapshotMaxThreads;
    }

    public String getSnapshotModeCustomName() {
        return snapshotModeCustomName;
    }

    public int getQueryFetchSize() {
        return queryFetchSize;
    }

    public int getIncrementalSnapshotChunkSize() {
        return incrementalSnapshotChunkSize;
    }

    public String getNotificationTopic() {
        return notificationTopicName;
    }

    public List<String> getEnabledNotificationChannels() {
        return enabledNotificationChannels;
    }

    public boolean shouldProvideTransactionMetadata() {
        return shouldProvideTransactionMetadata;
    }

    public boolean skipMessagesWithoutChange() {
        return skipMessagesWithoutChange;
    }

    public EventProcessingFailureHandlingMode getEventProcessingFailureHandlingMode() {
        return eventProcessingFailureHandlingMode;
    }

    /**
     * Whether a particular connector supports an optimized way for implementing operation skipping, or not.
     */
    public boolean supportsOperationFiltering() {
        return false;
    }

    protected boolean supportsSchemaChangesDuringIncrementalSnapshot() {
        return false;
    }

    public boolean isIncrementalSnapshotSchemaChangesEnabled() {
        return supportsSchemaChangesDuringIncrementalSnapshot() && incrementalSnapshotAllowSchemaChanges;
    }

    @SuppressWarnings("unchecked")
    public TopicNamingStrategy getTopicNamingStrategy(Field topicNamingStrategyField) {
        return getTopicNamingStrategy(topicNamingStrategyField, false);
    }

    @SuppressWarnings("unchecked")
    public TopicNamingStrategy getTopicNamingStrategy(Field topicNamingStrategyField, boolean multiPartitionMode) {
        Properties props = config.asProperties();
        props.put(MULTI_PARTITION_MODE, multiPartitionMode);
        String strategyName = config.getString(topicNamingStrategyField);
        TopicNamingStrategy topicNamingStrategy = config.getInstance(topicNamingStrategyField, TopicNamingStrategy.class, props);
        if (topicNamingStrategy == null) {
            throw new ConnectException("Unable to instantiate the topic naming strategy class " + strategyName);
        }
        LOGGER.info("Loading the custom topic naming strategy plugin: {}", strategyName);
        return topicNamingStrategy;
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractSourceInfo> SourceInfoStructMaker<T> getSourceInfoStructMaker() {
        return (SourceInfoStructMaker<T>) sourceInfoStructMaker;
    }

    public TransactionMetadataFactory getTransactionMetadataFactory() {
        return getTransactionMetadataFactory(TRANSACTION_METADATA_FACTORY);
    }

    public EnumSet<Envelope.Operation> getSkippedOperations() {
        return skippedOperations;
    }

    public List<String> getDataCollectionsToBeSnapshotted() {
        return Optional.ofNullable(config.getString(SNAPSHOT_MODE_TABLES))
                .map(expr -> Arrays.asList(Strings.RegExSplitter.split(expr)))
                .orElseGet(Collections::emptyList);
    }

    public Map<String, String> getCustomMetricTags() {
        return customMetricTags;
    }

    public Map<String, String> createCustomMetricTags(Configuration config) {
        // Keep the map custom metric tags sequence
        HashMap<String, String> result = new LinkedHashMap<>();

        String rawValue = config.getString(CUSTOM_METRIC_TAGS);
        if (Strings.isNullOrBlank(rawValue)) {
            return result;
        }

        List<String> values = Strings.listOf(rawValue, x -> x.split(","), String::trim);
        for (String v : values) {
            List<String> items = Strings.listOf(v, x -> x.split("="), String::trim);
            result.put(items.get(0), items.get(1));
        }
        if (result.size() < values.size()) {
            LOGGER.warn("There are duplicated key-value pairs: {}", rawValue);
        }

        return result;
    }

    public WatermarkStrategy getIncrementalSnapshotWatermarkingStrategy() {
        return incrementalSnapshotWatermarkingStrategy;
    }

    public EventConvertingFailureHandlingMode getEventConvertingFailureHandlingMode() {
        return eventConvertingFailureHandlingMode;
    }

    /**
     * @return true if the connector should emit messages about schema changes into a public facing
     * topic.
     */
    public boolean isSchemaChangesHistoryEnabled() {
        return false;
    }

    /**
     * @return true if the connector should emit messages which include table and column comments.
     */
    public boolean isSchemaCommentsHistoryEnabled() {
        return false;
    }

    /**
     * Validates the supplied fields in this configuration. Extra fields not described by the supplied
     * {@code fields} parameter will not be validated.
     *
     * @param fields the fields
     * @param problems the consumer to eb called with each problem; never null
     * @return {@code true} if the value is considered valid, or {@code false} if it is not valid
     */
    public boolean validate(Iterable<Field> fields, ValidationOutput problems) {
        return config.validate(fields, problems);
    }

    /**
     * Validate the supplied fields in this configuration. Extra fields not described by the supplied
     * {@code fields} parameter will not be validated.
     *
     * @param fields the fields
     * @param problems the consumer to be called with each problem; never null
     * @return {@code true} if the value is considered valid, or {@code false} if it is not valid
     */
    public boolean validateAndRecord(Iterable<Field> fields, Consumer<String> problems) {
        return config.validateAndRecord(fields, problems);
    }

    private static int validateMaxQueueSize(Configuration config, Field field, ValidationOutput problems) {
        int maxQueueSize = config.getInteger(field);
        int maxBatchSize = config.getInteger(MAX_BATCH_SIZE);
        int count = 0;
        if (maxQueueSize <= 0) {
            problems.accept(field, maxQueueSize, "A positive queue size is required");
            ++count;
        }
        if (maxQueueSize <= maxBatchSize) {
            problems.accept(field, maxQueueSize, "Must be larger than the maximum batch size");
            ++count;
        }
        return count;
    }

    protected static int validateSkippedOperation(Configuration config, Field field, ValidationOutput problems) {
        String operations = config.getString(field);

        if (operations == null || "none".equals(operations)) {
            return 0;
        }

        boolean noneSpecified = false;
        boolean operationsSpecified = false;
        for (String operation : operations.split(",")) {
            switch (operation.trim()) {
                case "none":
                    noneSpecified = true;
                    continue;
                case "r":
                case "c":
                case "u":
                case "d":
                case "t":
                    operationsSpecified = true;
                    continue;
                default:
                    problems.accept(field, operation, "Invalid operation");
                    return 1;
            }
        }

        if (noneSpecified && operationsSpecified) {
            problems.accept(field, "none", "'none' cannot be specified with other skipped operation types");
            return 1;
        }

        return 0;
    }

    private static boolean isUsingAvroConverter(Configuration config) {
        final String keyConverter = config.getString("key.converter");
        final String valueConverter = config.getString("value.converter");

        return CONFLUENT_AVRO_CONVERTER.equals(keyConverter) || CONFLUENT_AVRO_CONVERTER.equals(valueConverter)
                || APICURIO_AVRO_CONVERTER.equals(keyConverter) || APICURIO_AVRO_CONVERTER.equals(valueConverter);
    }

    public String snapshotLockingModeCustomName() {
        return this.snapshotLockingModeCustomName;
    }

    public boolean snapshotModeConfigurationBasedSnapshotData() {
        return this.snapshotModeConfigurationBasedSnapshotData;
    }

    public boolean snapshotModeConfigurationBasedSnapshotSchema() {
        return this.snapshotModeConfigurationBasedSnapshotSchema;
    }

    public boolean snapshotModeConfigurationBasedStream() {
        return this.snapshotModeConfigurationBasedStream;
    }

    public boolean snapshotModeConfigurationBasedSnapshotOnSchemaError() {
        return this.snapshotModeConfigurationBasedSnapshotOnSchemaError;
    }

    public boolean snapshotModeConfigurationBasedSnapshotOnDataError() {
        return this.snapshotModeConfigurationBasedSnapshotOnDataError;
    }

    public boolean isLogPositionCheckEnabled() {
        return isLogPositionCheckEnabled;
    }

    public boolean isAdvancedMetricsEnabled() {
        return isAdvancedMetricsEnabled;
    }

    public boolean isExtendedHeadersEnabled() {
        return isExtendedHeadersEnabled;
    }

    public Duration getConnectionValidationTimeout() {
        return Duration.ofMillis(config.getLong(CONNECTION_VALIDATION_TIMEOUT_MS));
    }

    public Duration getExecutorShutdownTimeout() {
        return Duration.ofMillis(config.getLong(EXECUTOR_SHUTDOWN_TIMEOUT_MS));
    }

    public EnumeratedValue snapshotQueryMode() {
        return this.snapshotQueryMode;
    }

    public String snapshotQueryModeCustomName() {
        return this.snapshotQueryModeCustomName;
    }

    /**
     * Returns the connector-specific {@link SourceInfoStructMaker} based on the given configuration.
     */
    protected abstract SourceInfoStructMaker<?> getSourceInfoStructMaker(Version version);

    public BinaryHandlingMode binaryHandlingMode() {
        return binaryHandlingMode;
    }

    public SchemaNameAdjuster schemaNameAdjuster() {
        return schemaNameAdjustmentMode.createAdjuster();
    }

    public SchemaNameAdjuster fieldNameAdjuster() {
        if (fieldNameAdjustmentMode == FieldNameAdjustmentMode.NONE && isUsingAvroConverter(config)) {
            return FieldNameAdjustmentMode.AVRO.createAdjuster();
        }
        return fieldNameAdjustmentMode.createAdjuster();
    }

    public String getSignalingDataCollectionId() {
        return signalingDataCollection;
    }

    public Duration getSignalPollInterval() {
        return signalPollInterval;
    }

    public List<String> getEnabledChannels() {
        return signalEnabledChannels;
    }

    public Optional<String[]> parseSignallingMessage(Struct value, String fieldName) {
        final Struct event = value.getStruct(fieldName);
        if (event == null) {
            LOGGER.warn("Field {} part of signal '{}' is missing", fieldName, value);
            return Optional.empty();
        }
        List<org.apache.kafka.connect.data.Field> fields = event.schema().fields();
        if (fields.size() != 3) {
            LOGGER.warn("The signal event '{}' should have 3 fields but has {}", event, fields.size());
            return Optional.empty();
        }
        return Optional.of(new String[]{
                event.getString(fields.get(0).name()),
                event.getString(fields.get(1).name()),
                event.getString(fields.get(2).name())
        });
    }

    public boolean isSignalDataCollection(DataCollectionId dataCollectionId) {
        return signalingDataCollectionId != null && signalingDataCollectionId.equals(dataCollectionId);
    }

    public Optional<String> customRetriableException() {
        return Optional.ofNullable(config.getString(CUSTOM_RETRIABLE_EXCEPTION));
    }

    public int getMaxRetriesOnError() {
        return maxRetriesOnError;
    }

    public String getTaskId() {
        return taskId;
    }

    /**
     * @deprecated Use {@link io.debezium.heartbeat.HeartbeatFactory} instead.
     */
    @Deprecated
    public Heartbeat createHeartbeat(TopicNamingStrategy topicNamingStrategy, SchemaNameAdjuster schemaNameAdjuster,
                                     HeartbeatConnectionProvider connectionProvider, HeartbeatErrorHandler errorHandler) {
        if (getHeartbeatInterval().isZero()) {
            return Heartbeat.DEFAULT_NOOP_HEARTBEAT;
        }
        return new HeartbeatImpl(getHeartbeatInterval(), topicNamingStrategy.heartbeatTopic(), getLogicalName(), schemaNameAdjuster);

    }

    public static int validateTopicName(Configuration config, Field field, ValidationOutput problems) {
        String name = config.getString(field);

        if (name != null) {
            if (!TOPIC_NAME_PATTERN.asPredicate().test(name)) {
                problems.accept(field, name, name + " has invalid format (only the underscore, hyphen, dot and alphanumeric characters are allowed)");
                return 1;
            }
        }
        return 0;
    }

    public <T extends AbstractSourceInfo> SourceInfoStructMaker<T> getSourceInfoStructMaker(Field sourceInfoStructMakerField, String connector, String version,
                                                                                            CommonConnectorConfig connectorConfig) {
        @SuppressWarnings("unchecked")
        final SourceInfoStructMaker<T> sourceInfoStructMaker = config.getInstance(sourceInfoStructMakerField, SourceInfoStructMaker.class);
        if (sourceInfoStructMaker == null) {
            throw new DebeziumException("Unable to instantiate the source info struct maker class " + config.getString(sourceInfoStructMakerField));
        }
        LOGGER.info("Loading the custom source info struct maker plugin: {}", sourceInfoStructMaker.getClass().getName());

        sourceInfoStructMaker.init(connector, version, connectorConfig);
        return sourceInfoStructMaker;
    }

    public TransactionMetadataFactory getTransactionMetadataFactory(Field transactionMetadataFactoryField) {
        final TransactionMetadataFactory factory = config.getInstance(transactionMetadataFactoryField, TransactionMetadataFactory.class, config);
        if (factory == null) {
            throw new DebeziumException("Unable to instantiate the transaction struct maker class " + TRANSACTION_METADATA_FACTORY);
        }
        return factory;
    }
}
