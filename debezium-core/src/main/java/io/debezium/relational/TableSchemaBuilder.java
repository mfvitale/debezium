/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.errors.SchemaBuilderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.annotation.Immutable;
import io.debezium.annotation.ThreadSafe;
import io.debezium.config.CommonConnectorConfig.EventConvertingFailureHandlingMode;
import io.debezium.data.Envelope;
import io.debezium.data.SchemaUtil;
import io.debezium.relational.Key.KeyMapper;
import io.debezium.relational.Tables.ColumnNameFilter;
import io.debezium.relational.mapping.ColumnMapper;
import io.debezium.relational.mapping.ColumnMappers;
import io.debezium.schema.FieldNameSelector.FieldNamer;
import io.debezium.schema.SchemaFactory;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.spi.topic.TopicNamingStrategy;
import io.debezium.util.Loggings;

/**
 * Builder that constructs {@link TableSchema} instances for {@link Table} definitions.
 * <p>
 * This builder is responsible for mapping {@link Column table columns} to {@link Field fields} in Kafka Connect {@link Schema}s,
 * and this is necessarily dependent upon the database's supported types. Although mappings are defined for standard types,
 * this class may need to be subclassed for each DBMS to add support for DBMS-specific types by overriding any of the
 * "{@code add*Field}" methods.
 * <p>
 * See the <a href="http://docs.oracle.com/javase/6/docs/technotes/guides/jdbc/getstart/mapping.html#table1">Java SE Mapping SQL
 * and Java Types</a> for details about how JDBC {@link Types types} map to Java value types.
 *
 * @author Randall Hauch
 */
@ThreadSafe
@Immutable
public class TableSchemaBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableSchemaBuilder.class);

    private final SchemaNameAdjuster schemaNameAdjuster;
    private final ValueConverterProvider valueConverterProvider;
    private final DefaultValueConverter defaultValueConverter;
    private final Schema sourceInfoSchema;
    private final Schema transactionSchema;
    private final FieldNamer<Column> fieldNamer;
    private final CustomConverterRegistry customConverterRegistry;
    private final boolean multiPartitionMode;
    private final EventConvertingFailureHandlingMode eventConvertingFailureHandlingMode;

    /**
     * Create a new instance of the builder.
     *
     * @param valueConverterProvider the provider for obtaining {@link ValueConverter}s and {@link SchemaBuilder}s; may not be
     *            null
     * @param schemaNameAdjuster the adjuster for schema names; may not be null
     */
    public TableSchemaBuilder(ValueConverterProvider valueConverterProvider,
                              SchemaNameAdjuster schemaNameAdjuster,
                              CustomConverterRegistry customConverterRegistry,
                              Schema sourceInfoSchema,
                              FieldNamer<Column> fieldNamer,
                              boolean multiPartitionMode) {
        this(valueConverterProvider, null, schemaNameAdjuster,
                customConverterRegistry, sourceInfoSchema, fieldNamer, multiPartitionMode, null);
    }

    public TableSchemaBuilder(ValueConverterProvider valueConverterProvider,
                              SchemaNameAdjuster schemaNameAdjuster,
                              CustomConverterRegistry customConverterRegistry,
                              Schema sourceInfoSchema,
                              Schema transactionSchema,
                              FieldNamer<Column> fieldNamer,
                              boolean multiPartitionMode) {
        this(valueConverterProvider, null, schemaNameAdjuster,
                customConverterRegistry, sourceInfoSchema, transactionSchema, fieldNamer,
                multiPartitionMode, null);
    }

    /**
     * Create a new instance of the builder.
     *
     * @param valueConverterProvider the provider for obtaining {@link ValueConverter}s and {@link SchemaBuilder}s; may not be
     *            null
     * @param schemaNameAdjuster the adjuster for schema names; may not be null
     */
    public TableSchemaBuilder(ValueConverterProvider valueConverterProvider,
                              DefaultValueConverter defaultValueConverter,
                              SchemaNameAdjuster schemaNameAdjuster,
                              CustomConverterRegistry customConverterRegistry,
                              Schema sourceInfoSchema,
                              FieldNamer<Column> fieldNamer,
                              boolean multiPartitionMode) {
        this(valueConverterProvider, defaultValueConverter, schemaNameAdjuster,
                customConverterRegistry, sourceInfoSchema, fieldNamer, multiPartitionMode, null);
    }

    public TableSchemaBuilder(ValueConverterProvider valueConverterProvider,
                              DefaultValueConverter defaultValueConverter,
                              SchemaNameAdjuster schemaNameAdjuster,
                              CustomConverterRegistry customConverterRegistry,
                              Schema sourceInfoSchema,
                              FieldNamer<Column> fieldNamer,
                              boolean multiPartitionMode,
                              EventConvertingFailureHandlingMode eventConvertingFailureHandlingMode) {
        this(valueConverterProvider, defaultValueConverter, schemaNameAdjuster,
                customConverterRegistry, sourceInfoSchema, SchemaFactory.get().transactionBlockSchema(),
                fieldNamer, multiPartitionMode, eventConvertingFailureHandlingMode);
    }

    /**
     * Create a new instance of the builder.
     *
     * @param valueConverterProvider the provider for obtaining {@link ValueConverter}s and {@link SchemaBuilder}s; may not be
     *            null
     * @param defaultValueConverter is used to convert the default value literal to a Java type
     *            recognized by value converters for a subset of types. may be null.
     * @param schemaNameAdjuster the adjuster for schema names; may not be null
     */
    public TableSchemaBuilder(ValueConverterProvider valueConverterProvider,
                              DefaultValueConverter defaultValueConverter,
                              SchemaNameAdjuster schemaNameAdjuster,
                              CustomConverterRegistry customConverterRegistry,
                              Schema sourceInfoSchema,
                              Schema transactionSchema,
                              FieldNamer<Column> fieldNamer,
                              boolean multiPartitionMode,
                              EventConvertingFailureHandlingMode eventConvertingFailureHandlingMode) {
        this.schemaNameAdjuster = schemaNameAdjuster;
        this.valueConverterProvider = valueConverterProvider;
        this.defaultValueConverter = Optional.ofNullable(defaultValueConverter)
                .orElse(DefaultValueConverter.passthrough());
        this.sourceInfoSchema = sourceInfoSchema;
        this.transactionSchema = transactionSchema;
        this.fieldNamer = fieldNamer;
        this.customConverterRegistry = customConverterRegistry;
        this.multiPartitionMode = multiPartitionMode;
        this.eventConvertingFailureHandlingMode = eventConvertingFailureHandlingMode;
    }

    /**
     * Create a {@link TableSchema} from the given {@link Table table definition}. The resulting TableSchema will have a
     * {@link TableSchema#keySchema() key schema} that contains all of the columns that make up the table's primary key,
     * and a {@link TableSchema#valueSchema() value schema} that contains only those columns that are not in the table's primary
     * key.
     * <p>
     * This is equivalent to calling {@code create(table,false)}.
     *
     * @param topicNamingStrategy the topic naming strategy
     * @param table the table definition; may not be null
     * @param filter the filter that specifies whether columns in the table should be included; may be null if all columns
     *            are to be included
     * @param mappers the mapping functions for columns; may be null if none of the columns are to be mapped to different values
     * @return the table schema that can be used for sending rows of data for this table to Kafka Connect; never null
     */
    public TableSchema create(TopicNamingStrategy topicNamingStrategy, Table table, ColumnNameFilter filter, ColumnMappers mappers, KeyMapper keysMapper) {
        // Build the schemas ...
        final TableId tableId = table.id();
        final String schemaNamePrefix = topicNamingStrategy.recordSchemaPrefix(tableId);
        final String envelopeSchemaPrefix = topicNamingStrategy.dataChangeTopic(tableId);
        final String envelopSchemaName = Envelope.schemaName(envelopeSchemaPrefix);
        LOGGER.debug("Mapping table '{}' to schemas under '{}'", tableId, schemaNamePrefix);
        SchemaBuilder valSchemaBuilder = SchemaBuilder.struct().name(schemaNameAdjuster.adjust(schemaNamePrefix + ".Value"));
        SchemaBuilder keySchemaBuilder = SchemaBuilder.struct().name(schemaNameAdjuster.adjust(schemaNamePrefix + ".Key"));
        AtomicBoolean hasPrimaryKey = new AtomicBoolean(false);

        Key tableKey = new Key.Builder(table).customKeyMapper(keysMapper).build();
        tableKey.keyColumns().forEach(column -> {
            addField(keySchemaBuilder, table, column, null);
            hasPrimaryKey.set(true);
        });
        if (topicNamingStrategy.keySchemaAugment().augment(keySchemaBuilder)) {
            hasPrimaryKey.set(true);
        }

        table.columns()
                .stream()
                .filter(column -> filter == null || filter.matches(tableId.catalog(), tableId.schema(), tableId.table(), column.name()))
                .forEach(column -> {
                    ColumnMapper mapper = mappers == null ? null : mappers.mapperFor(tableId, column);
                    addField(valSchemaBuilder, table, column, mapper);
                });

        Schema valSchema = valSchemaBuilder.optional().build();
        Schema keySchema = hasPrimaryKey.get() ? keySchemaBuilder.build() : null;

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Mapped primary key for table '{}' to schema: {}", tableId, SchemaUtil.asDetailedString(keySchema));
            LOGGER.debug("Mapped columns for table '{}' to schema: {}", tableId, SchemaUtil.asDetailedString(valSchema));
        }

        Envelope envelope = Envelope.defineSchema()
                .withName(schemaNameAdjuster.adjust(envelopSchemaName))
                .withRecord(valSchema)
                .withSource(sourceInfoSchema)
                .withTransaction(transactionSchema)
                .build();

        // Create the generators ...
        StructGenerator keyGenerator = createKeyGenerator(keySchema, tableId, tableKey.keyColumns(), topicNamingStrategy);
        StructGenerator valueGenerator = createValueGenerator(valSchema, tableId, table.columns(), filter, mappers);

        // And the table schema ...
        return new TableSchema(tableId, keySchema, keyGenerator, envelope, valSchema, valueGenerator);
    }

    public boolean isMultiPartitionMode() {
        return multiPartitionMode;
    }

    /**
     * Creates the function that produces a Kafka Connect key object for a row of data.
     *
     * @param schema the Kafka Connect schema for the key; may be null if there is no known schema, in which case the generator
     *            will be null
     * @param columnSetName the name for the set of columns, used in error messages; may not be null
     * @param columns the column definitions for the table that defines the row; may not be null
     * @param topicNamingStrategy the topic naming strategy
     * @return the key-generating function, or null if there is no key schema
     */
    protected StructGenerator createKeyGenerator(Schema schema, TableId columnSetName, List<Column> columns,
                                                 TopicNamingStrategy topicNamingStrategy) {
        if (schema != null) {
            int[] recordIndexes = indexesForColumns(columns);
            Field[] fields = fieldsForColumns(schema, columns);
            int numFields = recordIndexes.length;
            ValueConverter[] converters = convertersForColumns(schema, columnSetName, columns, null);
            return (row) -> {
                Struct result = new Struct(schema);
                for (int i = 0; i != numFields; ++i) {
                    validateIncomingRowToInternalMetadata(columnSetName, recordIndexes, fields, converters, row, i);
                    Object value = row[recordIndexes[i]];
                    ValueConverter converter = converters[i];
                    if (converter != null) {
                        // A component of primary key must be not-null.
                        // It is possible for some databases and values (MySQL and all-zero datetime)
                        // to be reported as null by JDBC or streaming reader.
                        // It thus makes sense to convert them to a sensible default replacement value.
                        value = converter.convert(value);
                        try {
                            result.put(fields[i], value);
                        }
                        catch (DataException e) {
                            Column col = columns.get(i);
                            Loggings.logErrorAndTraceRecord(LOGGER, row,
                                    "Failed to properly convert key value for '{}.{}' of type {}", columnSetName,
                                    col.name(), col.typeName(), e);
                        }
                    }
                }
                topicNamingStrategy.keyValueAugment().augment(columnSetName, schema, result);
                return result;
            };
        }
        return null;
    }

    private void validateIncomingRowToInternalMetadata(TableId tableId, int[] recordIndexes, Field[] fields, ValueConverter[] converters,
                                                       Object[] row, int position) {
        if (position >= converters.length) {
            LOGGER.error("Error requesting a converter, converters: {}, requested index: {} of {}", converters.length, position, tableId);
            throw new ConnectException(
                    "Column indexing array is larger than number of converters, internal schema representation is probably out of sync with real database schema");
        }
        if (position >= fields.length) {
            LOGGER.error("Error requesting a field, fields: {}, requested index: {} of {}", fields.length, position, tableId);
            throw new ConnectException("Too few schema fields, internal schema representation is probably out of sync with real database schema");
        }
        if (recordIndexes[position] >= row.length) {
            LOGGER.error("Error requesting a row value, row: {}, requested index: {} at position {} of {}", row.length, recordIndexes[position], position, tableId);
            throw new ConnectException("Data row is smaller than a column index, internal schema representation is probably out of sync with real database schema");
        }
    }

    /**
     * Creates the function that produces a Kafka Connect value object for a row of data.
     *
     * @param schema the Kafka Connect schema for the value; may be null if there is no known schema, in which case the generator
     *            will be null
     * @param tableId the table identifier; may not be null
     * @param columns the column definitions for the table that defines the row; may not be null
     * @param filter the filter that specifies whether columns in the table should be included; may be null if all columns
     *            are to be included
     * @param mappers the mapping functions for columns; may be null if none of the columns are to be mapped to different values
     * @return the value-generating function, or null if there is no value schema
     */
    protected StructGenerator createValueGenerator(Schema schema, TableId tableId, List<Column> columns,
                                                   ColumnNameFilter filter, ColumnMappers mappers) {
        if (schema != null) {
            List<Column> columnsThatShouldBeAdded = columns.stream()
                    .filter(column -> filter == null || filter.matches(tableId.catalog(), tableId.schema(), tableId.table(), column.name()))
                    .collect(Collectors.toList());
            int[] recordIndexes = indexesForColumns(columnsThatShouldBeAdded);
            Field[] fields = fieldsForColumns(schema, columnsThatShouldBeAdded);
            int numFields = recordIndexes.length;
            ValueConverter[] converters = convertersForColumns(schema, tableId, columnsThatShouldBeAdded, mappers);
            return (row) -> {
                Struct result = new Struct(schema);
                for (int i = 0; i != numFields; ++i) {
                    validateIncomingRowToInternalMetadata(tableId, recordIndexes, fields, converters, row, i);
                    Object value = row[recordIndexes[i]];

                    ValueConverter converter = converters[i];

                    if (converter != null) {
                        LOGGER.trace("converter for value object: *** {} ***", converter);
                        try {
                            value = converter.convert(value);
                            result.put(fields[i], value);
                        }
                        catch (final Exception e) {
                            Column col = columnsThatShouldBeAdded.get(i);
                            String message = "Failed to properly convert data value for '{}.{}' of type {}";
                            if (eventConvertingFailureHandlingMode == null) {
                                Loggings.logErrorAndTraceRecord(LOGGER, row,
                                        message, tableId, col.name(), col.typeName(), e);
                            }
                            else {
                                // NOTE: what if failed column is not accept null?
                                switch (eventConvertingFailureHandlingMode) {
                                    case FAIL:
                                        Loggings.logErrorAndTraceRecord(LOGGER, row, message, tableId,
                                                col.name(), col.typeName(), e);
                                        throw new DebeziumException("Failed to properly convert data value for '" +
                                                tableId + "." + col.name() + "' of type " + col.typeName(), e.getCause());
                                    case WARN:
                                        Loggings.logWarningAndTraceRecord(LOGGER, row, message, tableId,
                                                col.name(), col.typeName(), e);
                                    case SKIP:
                                        Loggings.logDebugAndTraceRecord(LOGGER, row, message, tableId,
                                                col.name(), col.typeName(), e);
                                }
                            }
                        }
                    }
                    else {
                        LOGGER.trace("converter is null...");
                    }
                }
                return result;
            };
        }
        return null;
    }

    protected int[] indexesForColumns(List<Column> columns) {
        int[] recordIndexes = new int[columns.size()];
        AtomicInteger i = new AtomicInteger(0);
        columns.forEach(column -> {
            recordIndexes[i.getAndIncrement()] = column.position() - 1; // position is 1-based, indexes 0-based
        });
        return recordIndexes;
    }

    protected Field[] fieldsForColumns(Schema schema, List<Column> columns) {
        Field[] fields = new Field[columns.size()];
        AtomicInteger i = new AtomicInteger(0);
        columns.forEach(column -> {
            Field field = schema.field(fieldNamer.fieldNameFor(column)); // may be null if the field is unused ...
            fields[i.getAndIncrement()] = field;
        });
        return fields;
    }

    /**
     * Obtain the array of converters for each column in a row. A converter might be null if the column is not be included in
     * the records.
     *
     * @param schema the schema; may not be null
     * @param tableId the identifier of the table that contains the columns
     * @param columns the columns in the row; may not be null
     * @param mappers the mapping functions for columns; may be null if none of the columns are to be mapped to different values
     * @return the converters for each column in the rows; never null
     */
    protected ValueConverter[] convertersForColumns(Schema schema, TableId tableId, List<Column> columns, ColumnMappers mappers) {

        ValueConverter[] converters = new ValueConverter[columns.size()];

        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);

            ValueConverter converter = createValueConverterFor(tableId, column, schema.field(fieldNamer.fieldNameFor(column)));
            converter = wrapInMappingConverterIfNeeded(mappers, tableId, column, converter);

            if (converter == null) {
                LOGGER.warn(
                        "No converter found for column {}.{} of type {}. The column will not be part of change events for that table.",
                        tableId, column.name(), column.typeName());
            }

            // may be null if no converter found
            converters[i] = converter;
        }

        return converters;
    }

    private ValueConverter wrapInMappingConverterIfNeeded(ColumnMappers mappers, TableId tableId, Column column, ValueConverter converter) {
        if (mappers == null || converter == null) {
            return converter;
        }

        ValueConverter mappingConverter = mappers.mappingConverterFor(tableId, column);
        if (mappingConverter == null) {
            return converter;
        }

        return (value) -> mappingConverter.convert(converter.convert(value));
    }

    /**
     * Add to the supplied {@link SchemaBuilder} a field for the column with the given information.
     *
     * @param builder the schema builder; never null
     * @param table the table definition; never null
     * @param column the column definition
     * @param mapper the mapping function for the column; may be null if the columns is not to be mapped to different values
     */
    protected void addField(SchemaBuilder builder, Table table, Column column, ColumnMapper mapper) {
        final Object defaultValue = parseDefaultValue(table.id(), column);

        final SchemaBuilder fieldBuilder = customConverterRegistry.registerConverterFor(table.id(), column, defaultValue)
                .orElse(valueConverterProvider.schemaBuilder(column));

        if (fieldBuilder != null) {
            if (mapper != null) {
                // Let the mapper add properties to the schema ...
                mapper.alterFieldSchema(column, fieldBuilder);
            }
            if (column.isOptional()) {
                fieldBuilder.optional();
            }

            if (column.comment() != null) {
                fieldBuilder.doc(column.comment());
            }

            // if the default value is provided
            if (column.hasDefaultValue() && defaultValue != null) {
                try {
                    // if the resolution of the default value resulted in null; there is no need to set it
                    // if the column isn't optional, the schema won't be set as such and therefore trying
                    // to set a null default value on a non-optional field schema will assert.
                    fieldBuilder
                            .defaultValue(customConverterRegistry.getValueConverter(table.id(), column)
                                    .orElse(ValueConverter.passthrough()).convert(defaultValue));
                }
                catch (SchemaBuilderException e) {
                    throw new DebeziumException("Failed to set field default value for '" + table.id() + "."
                            + column.name() + "' of type " + column.typeName() + ", the default value is "
                            + defaultValue + " of type " + defaultValue.getClass(), e);
                }
            }

            builder.field(fieldNamer.fieldNameFor(column), fieldBuilder.build());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("- field '{}' ({}{}) from column {}", column.name(), builder.isOptional() ? "OPTIONAL " : "",
                        fieldBuilder.type(),
                        column);
            }
        }
        else {
            LOGGER.warn("Unexpected JDBC type '{}' for column '{}' that will be ignored", column.jdbcType(), column.name());
        }
    }

    /**
     * Create a {@link ValueConverter} that can be used to convert row values for the given column into the Kafka Connect value
     * object described by the {@link Field field definition}. This uses the supplied {@link ValueConverterProvider} object.
     *
     * @param tableId the id of the table containing the column; never null
     * @param column the column describing the input values; never null
     * @param fieldDefn the definition for the field in a Kafka Connect {@link Schema} describing the output of the function;
     *            never null
     * @return the value conversion function; may not be null
     */
    protected ValueConverter createValueConverterFor(TableId tableId, Column column, Field fieldDefn) {
        return customConverterRegistry.getValueConverter(tableId, column).orElse(valueConverterProvider.converter(column, fieldDefn));
    }

    // parse default value of column.
    // if fail to parse, it's handled by value of eventConvertingFailureHandlingMode.
    private Object parseDefaultValue(TableId tableId, Column column) {
        try {
            return column.defaultValueExpression()
                    .flatMap(e -> defaultValueConverter.parseDefaultValue(column, e))
                    .orElse(null);
        }
        catch (Exception e) {
            String message = "Unexpected default value for JDBC type '{}.{}' and column '{}'";
            if (eventConvertingFailureHandlingMode == null) {
                Loggings.logErrorAndTraceRecord(LOGGER, column.defaultValueExpression(),
                        message, column.typeName(), column.name(), e);
            }
            else {
                switch (eventConvertingFailureHandlingMode) {
                    case FAIL:
                        Loggings.logErrorAndTraceRecord(LOGGER, column.defaultValueExpression(),
                                message, tableId, column.typeName(), column.name(), e);
                        throw new DebeziumException("Failed to properly convert default value for '" +
                                tableId + "." + column.name() + "' of type " + column.typeName(), e.getCause());
                    case WARN:
                        Loggings.logWarningAndTraceRecord(LOGGER, column.defaultValueExpression(),
                                message, tableId, column.typeName(), column.name(), e);
                        return null;
                    case SKIP:
                        Loggings.logDebugAndTraceRecord(LOGGER, column.defaultValueExpression(),
                                message, tableId, column.typeName(), column.name(), e);
                        return null;
                }
            }
        }
        return null;
    }
}
