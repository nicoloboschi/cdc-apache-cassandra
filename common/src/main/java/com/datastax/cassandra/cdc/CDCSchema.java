package com.datastax.cassandra.cdc;

import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.schema.JSONSchema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;

public class CDCSchema {

    public static final Schema<KeyValue<MutationKey, MutationValue>> kvSchema = Schema.KeyValue(
            JSONSchema.of(MutationKey.class),
            JSONSchema.of(MutationValue.class),
            KeyValueEncodingType.SEPARATED
    );

}
