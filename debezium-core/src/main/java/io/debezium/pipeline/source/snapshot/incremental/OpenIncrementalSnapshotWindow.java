/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.source.snapshot.incremental;

import io.debezium.pipeline.signal.actions.SignalAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.pipeline.signal.Signal.Payload;
import io.debezium.pipeline.spi.Partition;

public class OpenIncrementalSnapshotWindow<P extends Partition> implements SignalAction<P> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenIncrementalSnapshotWindow.class);

    public static final String NAME = "snapshot-window-open";

    public OpenIncrementalSnapshotWindow() {
    }

    @Override
    public boolean arrived(Payload<P> signalPayload) {
        signalPayload.offsetContext.getIncrementalSnapshotContext().openWindow(signalPayload.id);
        return true;
    }

}
