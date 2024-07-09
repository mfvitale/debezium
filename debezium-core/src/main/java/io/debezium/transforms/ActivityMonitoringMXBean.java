/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.transforms;

import io.opentelemetry.api.metrics.LongCounter;

import java.util.Map;

public interface ActivityMonitoringMXBean {

    //LongCounter getInsertCount();
    Map<String, Long> getInsertCount();
    Map<String, Long> getDeleteCount();
    Map<String, Long> getUpdateCount();
}
