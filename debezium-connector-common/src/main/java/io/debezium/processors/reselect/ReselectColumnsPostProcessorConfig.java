/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.processors.reselect;

import org.apache.kafka.common.config.ConfigDef;

import io.debezium.config.Field;

/**
 * Configuration fields for {@link ReselectColumnsPostProcessor}.
 */
public class ReselectColumnsPostProcessorConfig {

    public static final Field RESELECT_COLUMNS_INCLUDE_LIST = Field.create("reselect.columns.include.list")
            .withDisplayName("Column include list")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("A comma-separated list of regular expressions that match fully-qualified table names and column names " +
                    "(in the format '<table>:<column>') to include in reselection. " +
                    "If not specified, all columns are eligible for reselection (subject to exclude list).");

    public static final Field RESELECT_COLUMNS_EXCLUDE_LIST = Field.create("reselect.columns.exclude.list")
            .withDisplayName("Column exclude list")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("A comma-separated list of regular expressions that match fully-qualified table names and column names " +
                    "(in the format '<table>:<column>') to exclude from reselection.");

    public static final Field RESELECT_UNAVAILABLE_VALUES = Field.create("reselect.unavailable.values")
            .withDisplayName("Reselect unavailable values")
            .withType(ConfigDef.Type.BOOLEAN)
            .withDefault(true)
            .withWidth(ConfigDef.Width.SHORT)
            .withImportance(ConfigDef.Importance.HIGH)
            .withDescription("Whether to reselect columns that contain the unavailable value placeholder.");

    public static final Field RESELECT_NULL_VALUES = Field.create("reselect.null.values")
            .withDisplayName("Reselect null values")
            .withType(ConfigDef.Type.BOOLEAN)
            .withDefault(true)
            .withWidth(ConfigDef.Width.SHORT)
            .withImportance(ConfigDef.Importance.HIGH)
            .withDescription("Whether to reselect columns that have null values.");

    public static final Field RESELECT_USE_EVENT_KEY = Field.create("reselect.use.event.key")
            .withDisplayName("Use event key for reselection")
            .withType(ConfigDef.Type.BOOLEAN)
            .withDefault(false)
            .withWidth(ConfigDef.Width.SHORT)
            .withImportance(ConfigDef.Importance.MEDIUM)
            .withDescription("Whether to use the event key fields instead of primary key columns for reselection queries.");

    public static final Field ERROR_HANDLING_MODE = Field.create("reselect.error.handling.mode")
            .withDisplayName("Error Handling")
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR, 0))
            .withEnum(ReselectColumnsPostProcessor.ErrorHandlingMode.class, ReselectColumnsPostProcessor.ErrorHandlingMode.WARN)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.LOW)
            .withDescription("Specify how to handle error in case of lookup sql failure or empty reselection: " +
                    "'warn' only log the error; " +
                    "'fail' fail the connector with an error message.");
}
