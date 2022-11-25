package io.debezium.connector.mysql.transforms;

import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.data.Envelope;
import io.debezium.transforms.SmtManager;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.debezium.data.Envelope.FieldName.*;

public class ComputePartition<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComputePartition.class);

    public static final String FIELD_NAME_CONF = "partition.field.name";
    public static final String PARTITION_NUMBER_CONF = "partition.num";
    public static final int DEFAULT_PARTITION_NUMBER = 1;

    private static final Field PARTITION_NAME_FIELD = Field.create(FIELD_NAME_CONF)
            .withDisplayName("Name of the field used to calculate the topic partition")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("Name of the table field used to calculate destination partition for the event. " +
                    "HashCode of the value will be used to calculate the partition number");

    private static final Field PARTITION_NUMBER_FIELD = Field.create(PARTITION_NUMBER_CONF)
            .withDisplayName("Number of partition configured for the topic")
            .withType(ConfigDef.Type.INT)
            .withDefault(DEFAULT_PARTITION_NUMBER)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("Number of partition configured for the topic.");

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(FIELD_NAME_CONF, ConfigDef.Type.STRING, ConfigDef.Importance.MEDIUM,
                    "Bla bla bla")
            .define(PARTITION_NUMBER_CONF, ConfigDef.Type.INT, DEFAULT_PARTITION_NUMBER, ConfigDef.Importance.MEDIUM,
                    "Bla bla bla");

    private SmtManager<R> smtManager;
    private String fieldName;
    private Integer numberOfPartitions;

    @Override
    public R apply(R r) {

        if (r.value() == null || !smtManager.isValidEnvelope(r) || numberOfPartitions == 1) {
            return r;
        }

        final Struct envelope = (Struct) r.value();

        Struct payload = extractPayload(envelope);

        Object fieldValue = payload.get(fieldName);
        int partition = computePartition(fieldValue);

        LOGGER.debug("Message {} will be sent to partition {}", envelope, partition);

        return r.newRecord(r.topic(), partition,
               r.keySchema(),
               r.key(),
               r.valueSchema(),
               envelope,
               r.timestamp());
    }

    private int computePartition(Object fieldValue) {
        return fieldValue.hashCode() % numberOfPartitions;
    }

    private Struct extractPayload(Struct envelope) {

        String operation = envelope.getString(OPERATION);

        Struct struct = (Struct) envelope.get(AFTER);
        if(Envelope.Operation.DELETE.code().equals(operation)) {
            struct = (Struct) envelope.get(BEFORE);
        }
        return struct;
    }

    @Override
    public ConfigDef config() {
       return CONFIG_DEF;
    }

    @Override
    public void close() {

    }

    @Override
    public void configure(Map<String, ?> props) {
        final Configuration config = Configuration.from(props);
        smtManager = new SmtManager<>(config);
        fieldName = config.getString(PARTITION_NAME_FIELD);
        numberOfPartitions = config.getInteger(PARTITION_NUMBER_FIELD);
    }
}
