package com.datastax.cassandra.cdc.producer.converters;

import org.apache.cassandra.schema.TableMetadata;
import org.apache.pulsar.common.schema.SchemaType;

public class JsonConverter extends AbstractGenericConverter {

    public JsonConverter(TableMetadata tableMetadata) {
        super(tableMetadata, SchemaType.JSON);
    }

}