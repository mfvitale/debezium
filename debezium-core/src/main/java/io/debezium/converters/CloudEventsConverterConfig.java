/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.converters;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.storage.ConverterConfig;

import io.debezium.config.CommonConnectorConfig.SchemaNameAdjustmentMode;
import io.debezium.config.EnumeratedValue;
import io.debezium.converters.spi.CloudEventsMaker;
import io.debezium.converters.spi.SerializerType;

/**
 * Configuration options for {@link CloudEventsConverter CloudEventsConverter} instances.
 */
public class CloudEventsConverterConfig extends ConverterConfig {

    public static final String CLOUDEVENTS_SERIALIZER_TYPE_CONFIG = "serializer.type";
    public static final String CLOUDEVENTS_SERIALIZER_TYPE_DEFAULT = "json";
    private static final String CLOUDEVENTS_SERIALIZER_TYPE_DOC = "Specify a serializer to serialize CloudEvents values";

    public static final String CLOUDEVENTS_DATA_SERIALIZER_TYPE_CONFIG = "data.serializer.type";
    public static final String CLOUDEVENTS_DATA_SERIALIZER_TYPE_DEFAULT = "json";
    private static final String CLOUDEVENTS_DATA_SERIALIZER_TYPE_DOC = "Specify a serializer to serialize the data field of CloudEvents values";

    public static final String CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_CONFIG = "opentelemetry.tracing.attributes.enable";
    public static final boolean CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_DEFAULT = false;
    private static final String CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_DOC = "Specify whether to include OpenTelemetry tracing attributes to a cloud event";

    public static final String CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_CONFIG = "extension.attributes.enable";
    public static final boolean CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_DEFAULT = true;
    private static final String CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_DOC = "Specify whether to include extension attributes to a cloud event";

    public static final String CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_CONFIG = "schema.name.adjustment.mode";
    public static final String CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_DEFAULT = "avro";
    private static final String CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_DOC = "Specify how schema names should be adjusted for compatibility with the message converter used by the connector, including:"
            + "'avro' replaces the characters that cannot be used in the Avro type name with underscore (default)"
            + "'none' does not apply any adjustment";

    public static final String CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_CONFIG = "schema.cloudevents.name";
    public static final String CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_DEFAULT = null;
    private static final String CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_DOC = "Specify CloudEvents schema name under which the schema is registered in a Schema Registry";

    public static final String CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_CONFIG = "schema.data.name.source.header.enable";
    public static final boolean CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_DEFAULT = false;
    private static final String CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_DOC = "Specify whether CloudEvents.data schema name can be retrieved from the header";

    public static final String CLOUDEVENTS_METADATA_SOURCE_CONFIG = "metadata.source";
    public static final String CLOUDEVENTS_METADATA_SOURCE_DEFAULT = "value,id:generate,type:generate,traceparent:header,dataSchemaName:generate";
    private static final String CLOUDEVENTS_METADATA_SOURCE_DOC = "Specify from where to retrieve metadata";

    private static final ConfigDef CONFIG;

    static {
        CONFIG = ConverterConfig.newConfigDef();

        CONFIG.define(CLOUDEVENTS_SERIALIZER_TYPE_CONFIG, ConfigDef.Type.STRING, CLOUDEVENTS_SERIALIZER_TYPE_DEFAULT, ConfigDef.Importance.HIGH,
                CLOUDEVENTS_SERIALIZER_TYPE_DOC);
        CONFIG.define(CLOUDEVENTS_DATA_SERIALIZER_TYPE_CONFIG, ConfigDef.Type.STRING, CLOUDEVENTS_DATA_SERIALIZER_TYPE_DEFAULT, ConfigDef.Importance.HIGH,
                CLOUDEVENTS_DATA_SERIALIZER_TYPE_DOC);
        CONFIG.define(CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_CONFIG, ConfigDef.Type.BOOLEAN, CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_DEFAULT,
                ConfigDef.Importance.HIGH,
                CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_DOC);
        CONFIG.define(CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_CONFIG, ConfigDef.Type.BOOLEAN, CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_DEFAULT, ConfigDef.Importance.HIGH,
                CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_DOC);
        CONFIG.define(CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_CONFIG, ConfigDef.Type.STRING, CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_DEFAULT, ConfigDef.Importance.LOW,
                CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_DOC);
        CONFIG.define(CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_CONFIG, ConfigDef.Type.STRING, CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_DEFAULT, ConfigDef.Importance.LOW,
                CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_DOC);
        CONFIG.define(CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_CONFIG, ConfigDef.Type.BOOLEAN, CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_DEFAULT,
                ConfigDef.Importance.LOW,
                CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_DOC);
        CONFIG.define(CLOUDEVENTS_METADATA_SOURCE_CONFIG, ConfigDef.Type.LIST, CLOUDEVENTS_METADATA_SOURCE_DEFAULT, ConfigDef.Importance.HIGH,
                CLOUDEVENTS_METADATA_SOURCE_DOC);
    }

    public static ConfigDef configDef() {
        return CONFIG;
    }

    public CloudEventsConverterConfig(Map<String, ?> props) {
        super(CONFIG, props);
    }

    /**
     * Return which serializer type is used to serialize CloudEvents values.
     *
     * @return serializer type
     */
    public SerializerType cloudeventsSerializerType() {
        return SerializerType.withName(getString(CLOUDEVENTS_SERIALIZER_TYPE_CONFIG));
    }

    /**
     * Return which serializer type is used to serialize the data field of CloudEvents values.
     *
     * @return serializer type
     */
    public SerializerType cloudeventsDataSerializerTypeConfig() {
        return SerializerType.withName(getString(CLOUDEVENTS_DATA_SERIALIZER_TYPE_CONFIG));
    }

    /**
     * Return whether to include OpenTelemetry tracing attributes in a cloud event.
     *
     * @return whether to enable OpenTelemetry tracing attributes
     */
    public boolean openTelemetryTracingAttributesEnable() {
        return getBoolean(CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_CONFIG);
    }

    /**
     * Return whether to include extension attributes in a cloud event.
     *
     * @return whether to enable extension attributes
     */
    public boolean extensionAttributesEnable() {
        return getBoolean(CLOUDEVENTS_EXTENSION_ATTRIBUTES_ENABLE_CONFIG);
    }

    /**
     * Return which adjustment mode is used to build message schema names.
     *
     * @return schema name adjustment mode
     */
    public SchemaNameAdjustmentMode schemaNameAdjustmentMode() {
        return SchemaNameAdjustmentMode.parse(getString(CLOUDEVENTS_SCHEMA_NAME_ADJUSTMENT_MODE_CONFIG));
    }

    /**
     * Return CloudEvents schema name under which the schema is registered in a Schema Registry
     *
     * @return CloudEvents schema name
     */
    public String schemaCloudEventsName() {
        return getString(CLOUDEVENTS_SCHEMA_CLOUDEVENTS_NAME_CONFIG);
    }

    /**
     * Return from where to retrieve metadata
     *
     * @return metadata source
     */
    public MetadataSource metadataSource() {
        final List<String> metadataSources = getList(CLOUDEVENTS_METADATA_SOURCE_CONFIG);
        final boolean openTelemetryTracingAttributesEnable = getBoolean(CLOUDEVENTS_OPENTELEMETRY_TRACING_ATTRIBUTES_ENABLE_CONFIG);
        final boolean dataSchemaNameFromHeaderEnable = getBoolean(CLOUDEVENTS_SCHEMA_DATA_NAME_SOURCE_HEADERS_ENABLE_CONFIG);

        // get global metadata source
        final Set<MetadataSourceValue> globalMetadataSourceAllowedValues = Set.of(MetadataSourceValue.VALUE, MetadataSourceValue.HEADER);
        final MetadataSourceValue global = MetadataSourceValue.parse(metadataSources.get(0));
        if (!globalMetadataSourceAllowedValues.contains(global)) {
            throw new ConfigException("Global metadata source can't be " + global.name());
        }

        // get sources for customizable fields
        Set<MetadataSourceValue> fieldMetadataSourceAllowedValues = Set.of(MetadataSourceValue.HEADER, MetadataSourceValue.GENERATE);
        MetadataSourceValue idCustomSource = null;
        MetadataSourceValue typeCustomSource = null;
        MetadataSourceValue traceParentCustomSource = null;
        MetadataSourceValue dataSchemaNameCustomSource = null;
        for (int i = 1; i < metadataSources.size(); i++) {
            final String[] parts = metadataSources.get(i).split(":");
            final String fieldName = parts[0];
            final MetadataSourceValue fieldSource = MetadataSourceValue.parse(parts[1]);
            if (fieldSource == null) {
                throw new ConfigException("Field source `" + parts[1] + "` is not valid");
            }
            if (!fieldMetadataSourceAllowedValues.contains(fieldSource)) {
                throw new ConfigException("Field metadata source can't be " + fieldSource.name());
            }
            switch (fieldName) {
                case CloudEventsMaker.FieldName.ID:
                    idCustomSource = fieldSource;
                    break;
                case CloudEventsMaker.FieldName.TYPE:
                    typeCustomSource = fieldSource;
                    break;
                case CloudEventsMaker.FieldName.TRACE_PARENT:
                    traceParentCustomSource = fieldSource;
                    break;
                case CloudEventsMaker.DATA_SCHEMA_NAME_PARAM:
                    dataSchemaNameCustomSource = fieldSource;
                    break;
                default:
                    throw new ConfigException("Field `" + fieldName + "` is not allowed to set custom source");
            }
        }

        // set final source values
        final MetadataSourceValue idSource = idCustomSource != null ? idCustomSource : global;
        final MetadataSourceValue typeSource = typeCustomSource != null ? typeCustomSource : global;
        MetadataSourceValue traceParentSource = traceParentCustomSource != null ? traceParentCustomSource : global;
        MetadataSourceValue dataSchemaNameSource = dataSchemaNameCustomSource != null ? dataSchemaNameCustomSource : global;

        // to obtain a value for `traceparent` field from the header, it is required to configure its source to `header` (by specifying custom source or
        // using global setting) and additionally enable inclusion of OpenTelemerey tracing attributes. it is done to preserve backward compatibility
        final boolean setDefaultTraceParentSource = traceParentSource == MetadataSourceValue.HEADER && !openTelemetryTracingAttributesEnable;
        if (setDefaultTraceParentSource) {
            // this setting just marks that the source is NOT `HEADER`
            traceParentSource = MetadataSourceValue.GENERATE;
        }

        // to obtain CloudEvents.data schema name from the header, it is required to configure its source to `header` (by specifying custom source or
        // using global setting) and additionally enable that feature explicitly. it is done to preserve backward compatibility
        final boolean setDefaultDataSchemaNameSource = dataSchemaNameSource == MetadataSourceValue.HEADER && !dataSchemaNameFromHeaderEnable;
        if (setDefaultDataSchemaNameSource) {
            dataSchemaNameSource = MetadataSourceValue.GENERATE;
        }

        return new MetadataSource(global, idSource, typeSource, traceParentSource, dataSchemaNameSource);
    }

    public class MetadataSource {
        private final MetadataSourceValue global;
        private final MetadataSourceValue id;
        private final MetadataSourceValue type;
        private final MetadataSourceValue traceParent;
        private final MetadataSourceValue dataSchemaName;

        public MetadataSource(MetadataSourceValue global, MetadataSourceValue id, MetadataSourceValue type, MetadataSourceValue traceParent,
                              MetadataSourceValue dataSchemaName) {
            this.global = global;
            this.id = id;
            this.type = type;
            this.traceParent = traceParent;
            this.dataSchemaName = dataSchemaName;
        }

        public MetadataSourceValue global() {
            return global;
        }

        public MetadataSourceValue id() {
            return id;
        }

        public MetadataSourceValue type() {
            return type;
        }

        public MetadataSourceValue traceParent() {
            return traceParent;
        }

        public MetadataSourceValue dataSchemaName() {
            return dataSchemaName;
        }
    }

    /**
     * The set of predefined MetadataSourceValue options
     */
    public enum MetadataSourceValue implements EnumeratedValue {

        /**
         * Get metadata from the value
         */
        VALUE("value"),

        /**
         * Get metadata from the header
         */
        HEADER("header"),

        /**
         * Generate a field's value
         */
        GENERATE("generate");

        private final String value;

        MetadataSourceValue(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied values is one of the predefined options
         *
         * @param value the configuration property value ; may not be null
         * @return the matching option, or null if the match is not found
         */
        public static MetadataSourceValue parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (MetadataSourceValue option : MetadataSourceValue.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }
    }
}
