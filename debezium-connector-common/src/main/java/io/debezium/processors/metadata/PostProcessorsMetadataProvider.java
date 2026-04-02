/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.processors.metadata;

import java.util.List;

import io.debezium.Module;
import io.debezium.metadata.ComponentMetadata;
import io.debezium.metadata.ComponentMetadataFactory;
import io.debezium.metadata.ComponentMetadataProvider;
import io.debezium.processors.reselect.ReselectColumnsPostProcessor;

/**
 * Aggregator for all Debezium post-processor metadata.
 */
public class PostProcessorsMetadataProvider implements ComponentMetadataProvider {

    private final ComponentMetadataFactory componentMetadataFactory = new ComponentMetadataFactory();

    @Override
    public List<ComponentMetadata> getConnectorMetadata() {
        return List.of(
                componentMetadataFactory.createComponentMetadata(new ReselectColumnsPostProcessor(), Module.version()));
    }

}
