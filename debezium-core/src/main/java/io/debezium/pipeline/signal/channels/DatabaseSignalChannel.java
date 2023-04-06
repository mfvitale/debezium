/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.signal.channels;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.NotThreadSafe;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.pipeline.signal.SignalRecord;

/**
 * The class responsible for processing of signals delivered to Debezium via a dedicated signaling table.
 * The processor supports a common set of signals that it can process and every connector can register its own
 * additional signals.
 * The signalling table must conform to the structure
 * <ul>
 * <li>{@code id STRING} - the unique identifier of the signal sent, usually UUID, can be used for deduplication</li>
 * <li>{@code type STRING} - the unique logical name of the code executing the signal</li>
 * <li>{@code data STRING} - the data in JSON format that are passed to the signal code
 * </ul>
 *
 * @author Jiri Pechanec
 *
 */
@NotThreadSafe
public class DatabaseSignalChannel implements SignalChannelReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSignalChannel.class);
    public static final Queue<SignalRecord> SIGNALS = new ConcurrentLinkedQueue<>();

    public CommonConnectorConfig connectorConfig;

    @Override
    public String name() {
        return "database";
    }

    @Override
    public void init(CommonConnectorConfig connectorConfig) {

        this.connectorConfig = connectorConfig;
    }

    @Override
    public List<SignalRecord> read() {

        LOGGER.trace("Reading signaling events from queue");

        SignalRecord signalRecord = SIGNALS.poll();
        if (signalRecord == null) {
            return List.of();
        }

        return List.of(signalRecord);
    }

    @Override
    public void close() {
    }

    /** Used in streaming flow to add signals from signaling table
     *
     * @param value Envelope with change from signaling table
     * @return true if the signal was processed
     */
    public boolean process(Struct value) throws InterruptedException { // TODO manage partition and offset

        LOGGER.trace("Received event from signaling table. Enqueue for process");
        try {
            Optional<SignalRecord> result = SignalRecord.buildSignalRecordFromChangeEventSource(value, connectorConfig);
            if (result.isEmpty()) {
                return false;
            }
            /*
             * TODO I think can be removed
             * Struct source = null;
             * if (value.schema().field(Envelope.FieldName.SOURCE) != null) {
             * source = value.getStruct(Envelope.FieldName.SOURCE);
             * }
             */

            final SignalRecord signalRecord = result.get();
            SIGNALS.add(signalRecord);
            return true;

        }
        catch (Exception e) {
            LOGGER.warn("Exception while preparing to process the signal '{}'", value, e);
            return false;
        }
    }
}
