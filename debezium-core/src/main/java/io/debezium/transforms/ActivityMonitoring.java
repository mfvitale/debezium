/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.transforms;

import io.debezium.data.Envelope;
import io.debezium.pipeline.JmxUtils;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class ActivityMonitoring <R extends ConnectRecord<R>> implements Transformation<R>, ActivityMonitoringMXBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityMonitoring.class);
    public static final String INSTRUMENTATION_SCOPE = "io.debezium.poc.metrics";
    //private Meter meter;
    //private LongCounter counter;
    private final ActivityCounter insertCount = new ActivityCounter();
    private final ActivityCounter updateCount = new ActivityCounter();
    private final ActivityCounter deleteCount = new ActivityCounter();

    @Override
    public R apply(R record) {

        //OpenTelemetry sdk = OpenTelemetrySdk.builder().build();
        //meter = sdk.getMeter(INSTRUMENTATION_SCOPE);
        //counter = createCounter();
        LOGGER.info("Received record {}", record);
        Struct payload = (Struct) record.value();
        Struct source = (Struct) payload.get("source");
        String tableName = source.getString("table");
        switch (Envelope.Operation.forCode(payload.getString("op"))) {
            case CREATE:
                LOGGER.info("Insert record");
                insertCount.add(1, tableName);
                break;
            case UPDATE:
                LOGGER.info("Update record");
                updateCount.add(1, tableName);
                break;
            case DELETE:
                LOGGER.info("Delete record");
                deleteCount.add(1, tableName);
                break;
            default:
                break;
        }

        LOGGER.info("Counter status insert:{}, delete:{}, update:{}", insertCount, deleteCount, updateCount);
        return record;
    }

    /**
     * Uses the Meter instance to create a LongCounter with the given name, description, and units.
     * This is the counter that is used to count directories during filesystem traversal.
     */
   /* LongCounter createCounter() {
        return meter
                .counterBuilder("insert_count")
                .setDescription("Counts inserts operations on the database.")
                .setUnit("unit")
                .build();
    }*/

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void close() {

    }

    @Override
    public void configure(Map<String, ?> map) {

        try {
            JmxUtils.registerMXBean(new ObjectName("debezium.postgresql:type=connector-metrics,context=streaming"), this);
        }
        catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, Long> getInsertCount() {
        return insertCount.getCounter();
    }

    @Override
    public Map<String, Long> getDeleteCount() {
        return deleteCount.getCounter();
    }

    @Override
    public Map<String, Long> getUpdateCount() {
        return updateCount.getCounter();
    }

    /*@Override
    public LongCounter getInsertCount() {
        return counter;
    }*/

    public static class ActivityCounter {

        private final ConcurrentMap<String, AtomicLong> counterByCollection = new ConcurrentHashMap<>();

        public ActivityCounter() {
        }

        public void add(int increment, String tableName) {

            counterByCollection.putIfAbsent(tableName, new AtomicLong(0));
            counterByCollection.compute(tableName, (k, v) -> {

                if (v == null) {
                    return new AtomicLong(1);
                }

                v.incrementAndGet();

                return v;
            });

        }

        public Map<String, Long> getCounter() {
            return counterByCollection.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e-> e.getValue().get()));
        }
    }
}
