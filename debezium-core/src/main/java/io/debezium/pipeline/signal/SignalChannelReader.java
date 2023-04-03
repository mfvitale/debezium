/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.signal;

import java.util.List;

/**
 * This interface is used to provide more read channel for the Debezium signaling feature:
 *
 * Implementations must:
 * define the name of the reader in {@link #name()},
 * initialize specific configuration/variables/connections in the {@link #init()} method,
 * provide a list of signal record in the {@link #read()} method. It is called by {@link SignalProcessor}
 * Close all allocated resources int the {@link #close()} method.
 *
 * @author Mario Fiore Vitale
 */
public interface SignalChannelReader { // TODO add java doc
    String name();

    void init();

    List<SignalRecord> read();

    void close();
}
