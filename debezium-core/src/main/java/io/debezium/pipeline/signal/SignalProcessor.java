/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.signal;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.document.Document;
import io.debezium.document.DocumentReader;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.signal.actions.ExecuteSnapshot;
import io.debezium.pipeline.signal.actions.Log;
import io.debezium.pipeline.signal.actions.PauseIncrementalSnapshot;
import io.debezium.pipeline.signal.actions.ResumeIncrementalSnapshot;
import io.debezium.pipeline.signal.actions.SchemaChanges;
import io.debezium.pipeline.signal.actions.SignalAction;
import io.debezium.pipeline.signal.actions.StopSnapshot;
import io.debezium.pipeline.source.snapshot.incremental.CloseIncrementalSnapshotWindow;
import io.debezium.pipeline.source.snapshot.incremental.OpenIncrementalSnapshotWindow;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.HistorizedRelationalDatabaseConnectorConfig;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.util.Threads;

/**
 * This class permits to process signals coming from the different channels.
 *
 * @author Mario Fiore Vitale
 */
public class SignalProcessor<P extends Partition> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignalProcessor.class);

    private final Map<String, SignalAction<P>> signalActions = new HashMap<>();

    private final CommonConnectorConfig connectorConfig;

    private final List<SignalChannelReader> signalChannelReaders;

    private final ScheduledExecutorService signalProcessorExecutor;

    private final DocumentReader documentReader;

    public SignalProcessor(Class<? extends SourceConnector> connector,
                           CommonConnectorConfig config,
                           EventDispatcher<P, ? extends DataCollectionId> eventDispatcher,
                           List<SignalChannelReader> signalChannelReaders, DocumentReader documentReader) {

        this.connectorConfig = config;
        this.signalChannelReaders = signalChannelReaders;
        this.documentReader = documentReader;
        this.signalProcessorExecutor = Threads.newSingleThreadScheduledExecutor(connector, config.getLogicalName(), SignalProcessor.class.getName(), false);

        signalChannelReaders.stream()
                .filter(isEnabled())
                .forEach(SignalChannelReader::read);

        registerSignalAction(Log.NAME, new Log<>());
        if (connectorConfig instanceof HistorizedRelationalDatabaseConnectorConfig) {
            registerSignalAction(SchemaChanges.NAME,
                    new SchemaChanges<>(eventDispatcher, ((HistorizedRelationalDatabaseConnectorConfig) connectorConfig).useCatalogBeforeSchema()));
        }
        else {
            registerSignalAction(SchemaChanges.NAME, new SchemaChanges<>(eventDispatcher, false));
        }

        registerSignalAction(ExecuteSnapshot.NAME, new ExecuteSnapshot<>(eventDispatcher));
        registerSignalAction(StopSnapshot.NAME, new StopSnapshot<>(eventDispatcher));
        registerSignalAction(OpenIncrementalSnapshotWindow.NAME, new OpenIncrementalSnapshotWindow<>());
        registerSignalAction(CloseIncrementalSnapshotWindow.NAME, new CloseIncrementalSnapshotWindow<>(eventDispatcher));
        registerSignalAction(PauseIncrementalSnapshot.NAME, new PauseIncrementalSnapshot<>(eventDispatcher));
        registerSignalAction(ResumeIncrementalSnapshot.NAME, new ResumeIncrementalSnapshot<>(eventDispatcher));
    }

    private Predicate<SignalChannelReader> isEnabled() {
        return reader -> connectorConfig.getEnabledChannels().contains(reader.name());
    }

    public void start() {

        signalProcessorExecutor.schedule(this::process, connectorConfig.getSignalPollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {

        signalProcessorExecutor.shutdown();
    }

    public void registerSignalAction(String id, SignalAction<P> signal) {

        LOGGER.debug("Registering signal '{}' using class '{}'", id, signal.getClass().getName());
        signalActions.put(id, signal);
    }

    private void process() {

        signalChannelReaders.parallelStream()
                .filter(isEnabled())
                .map(SignalChannelReader::read)
                .flatMap(Collection::stream)
                .forEach(this::processSignal);
    }

    private void processSignal(SignalRecord signalRecord) {

        LOGGER.debug("Received signal id = '{}', type = '{}', data = '{}'", signalRecord.getId(), signalRecord.getType(), signalRecord.getData());
        final SignalAction<P> action = signalActions.get(signalRecord.getType());
        if (action == null) {
            LOGGER.warn("Signal '{}' has been received but the type '{}' is not recognized", signalRecord.getId(), signalRecord.getType());
            return;
        }
        try {
            final Document jsonData = (signalRecord.getData() == null || signalRecord.getData().isEmpty()) ? Document.create()
                    : documentReader.read(signalRecord.getData());
            action.arrived(new SignalPayload<>(null, signalRecord.getId(), signalRecord.getType(), jsonData, null, null));
        }
        catch (IOException e) {
            LOGGER.warn("Signal '{}' has been received but the data '{}' cannot be parsed", signalRecord.getId(), signalRecord.getData(), e);
        }
        catch (InterruptedException e) {
            LOGGER.warn("Action {} has been interrupted. The signal {} may not have been processed.", signalRecord.getType(), signalRecord);
        }
    }
}
