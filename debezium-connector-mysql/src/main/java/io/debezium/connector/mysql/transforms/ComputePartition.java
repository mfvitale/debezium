/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql.transforms;

import static io.debezium.data.Envelope.FieldName.AFTER;
import static io.debezium.data.Envelope.FieldName.BEFORE;
import static io.debezium.data.Envelope.FieldName.OPERATION;
import static io.debezium.data.Envelope.FieldName.SOURCE;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.data.Envelope;
import io.debezium.transforms.SmtManager;

public class ComputePartition<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComputePartition.class);

    public static final String FIELD_TABLE_FIELD_NAME_MAPPINGS_CONF = "partition.table.field.mappings";
    public static final String FIELD_TABLE_PARTITION_NUM_MAPPINGS_CONF = "partition.table.partition.num.mappings";
    public static final String FIELD_TABLE_LIST_CONF = "partition.table.list";

    public static final String DEFAULT_PARTITION_NUMBER_CONF = "topic.creation.default.partitions";

    private static final Field PARTITION_TABLE_LIST_FIELD = Field.create(FIELD_TABLE_LIST_CONF)
            .withDisplayName("List of the table")
            .withType(ConfigDef.Type.LIST)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("......");

    private static final Field PARTITION_TABLE_FIELD_NAME_MAPPINGS_FIELD = Field.create(FIELD_TABLE_FIELD_NAME_MAPPINGS_CONF)
            .withDisplayName("Name of the field used to calculate the topic partition")
            .withType(ConfigDef.Type.STRING)
            .withValidation(ComputePartition::isValidMapping)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("list of colon-delimited pairs, e.g. <code>foo:bar,abc:xyz</code>");

    private static final Field FIELD_TABLE_PARTITION_NUM_MAPPINGS_FIELD = Field.create(FIELD_TABLE_PARTITION_NUM_MAPPINGS_CONF)
            .withDisplayName("Name of the field used to calculate the topic partition")
            .withType(ConfigDef.Type.STRING)
            .withValidation(ComputePartition::isValidMapping)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("list of colon-delimited pairs, e.g. <code>foo:1,abc:2</code>");
    private List<String> tableNames;

    public static int isValidMapping(Configuration config, Field field, Field.ValidationOutput problems) {
        List<String> values = config.getStrings(field, ",");
        try {
            parseMappings(values);
            return 0;
        }
        catch (Exception e) {
        }
        problems.accept(field, values, "list of colon-delimited pairs, e.g. <code>foo:bar,abc:xyz</code>");
        return 1;
    }

    static Map<String, String> parseMappings(List<String> mappings) {
        final Map<String, String> m = new HashMap<>();
        for (String mapping : mappings) {
            final String[] parts = mapping.split(":");
            if (parts.length != 2) {
                throw new RuntimeException("Invalid rename mapping: " + mapping);
            }
            m.put(parts[0], parts[1]);
        }
        return m;
    }

    static Map<String, Integer> parseIntMappings(List<String> mappings) {
        final Map<String, Integer> m = new HashMap<>();
        for (String mapping : mappings) {
            final String[] parts = mapping.split(":");
            if (parts.length != 2) {
                throw new RuntimeException("Invalid rename mapping: " + mapping);
            }
            try {
                int value = Integer.parseInt(parts[1]);
                m.put(parts[0], value);
            }
            catch (NumberFormatException e) {
                throw new RuntimeException("Invalid mapping value: " + e.getMessage());
            }
        }
        return m;
    }

    private SmtManager<R> smtManager;
    private Map<String, Integer> numberOfPartitionsByTable;
    private Map<String, String> fieldNameByTable;

    @Override
    public R apply(R r) {

        LOGGER.info("Starting ComputePartition STM {} {} {}", tableNames, fieldNameByTable, numberOfPartitionsByTable);

        if (r.value() == null || !smtManager.isValidEnvelope(r)) {
            LOGGER.info("Skipping {}", numberOfPartitionsByTable);
            return r;
        }

        final Struct envelope = (Struct) r.value();
        final String table = getTableName(envelope);
        if (!tableNames.contains(table)) {
            LOGGER.info("Table {} is not configured. Skipping STM", table);
            return r;
        }
        if (!fieldNameByTable.containsKey(table)) {
            LOGGER.info("No field name property defined for table {}. Skipping STM", table);
            return r;
        }
        if (!numberOfPartitionsByTable.containsKey(table)) {
            LOGGER.info("No number of partition property defined for table {}. Skipping STM", table);
            return r;
        }
        // TODO manage custom group configuration https://debezium.io/documentation/reference/stable/configuration/topic-auto-create-config.html#custom-topic-creation-group-configuration
        try {
            Struct payload = extractPayload(envelope);

            Object fieldValue = payload.get(fieldNameByTable.get(table));
            int partition = computePartition(fieldValue, table);

            LOGGER.info("Message {} will be sent to partition {}", envelope, partition);

            return r.newRecord(r.topic(), partition,
                    r.keySchema(),
                    r.key(),
                    r.valueSchema(),
                    envelope,
                    r.timestamp());
        }
        catch (Exception e) {
            LOGGER.warn("No field found");
            return r;
        }
    }

    private String getTableName(Struct envelope) {

        Struct struct = (Struct) envelope.get(SOURCE);
        return struct.getString("table");
    }

    private int computePartition(Object fieldValue, String table) {
        return fieldValue.hashCode() % numberOfPartitionsByTable.get(table);
    }

    private Struct extractPayload(Struct envelope) {

        String operation = envelope.getString(OPERATION);

        Struct struct = (Struct) envelope.get(AFTER);
        if (Envelope.Operation.DELETE.code().equals(operation)) {
            struct = (Struct) envelope.get(BEFORE);
        }
        return struct;
    }

    @Override
    public ConfigDef config() {
        ConfigDef config = new ConfigDef();
        // TODO group doesnt not manage validator definition
        return Field.group(config, "partitions",
                PARTITION_TABLE_FIELD_NAME_MAPPINGS_FIELD, FIELD_TABLE_PARTITION_NUM_MAPPINGS_FIELD, PARTITION_TABLE_LIST_FIELD);
    }

    @Override
    public void configure(Map<String, ?> props) {
        final Configuration config = Configuration.from(props);
        smtManager = new SmtManager<>(config);
        smtManager.validate(config, Field.setOf(PARTITION_TABLE_FIELD_NAME_MAPPINGS_FIELD));

        tableNames = config.getStrings(PARTITION_TABLE_LIST_FIELD, ",");
        numberOfPartitionsByTable = parseIntMappings(config.getStrings(FIELD_TABLE_PARTITION_NUM_MAPPINGS_FIELD, ","));
        fieldNameByTable = parseMappings(config.getStrings(PARTITION_TABLE_FIELD_NAME_MAPPINGS_FIELD, ","));

    }

    @Override
    public void close() {

    }
}
