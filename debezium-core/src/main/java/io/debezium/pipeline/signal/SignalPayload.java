/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.signal;

import org.apache.kafka.connect.data.Struct;

import io.debezium.document.Document;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;

public class SignalPayload<P extends Partition> {
    public final String id;
    public final String type;
    public final Document data;
    public final P partition; // TODO this make sense only for snapshotting actions. This really confuse me
    public final OffsetContext offsetContext; // TODO this make sense only for snapshotting actions
    public final Struct source; // TODO I think this is not useful in the signal flow. Since this should be the source of the signal event only in case of database signal

    /**
     * @param partition partition from which the signal was sent
     * @param id identifier of the signal intended for deduplication, usually ignored by the signal
     * @param type of the signal, usually ignored by the signal, should be used only when a signal code is shared for mutlple signals
     * @param data data specific for given signal instance
     * @param offsetContext offset at what the signal was sent
     * @param source source info about position at what the signal was sent
     */
    public SignalPayload(P partition, String id, String type, Document data, OffsetContext offsetContext, Struct source) {
        super();
        this.partition = partition;
        this.id = id;
        this.type = type;
        this.data = data;
        this.offsetContext = offsetContext;
        this.source = source;
    }

    @Override
    public String toString() {
        return "Payload [id=" + id + ", type=" + type + ", data=" + data + ", offsetContext=" + offsetContext
                + ", source=" + source + "]";
    }

}
