package org.corfudb.runtime.collections;

import com.google.protobuf.Message;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

@Builder(access = AccessLevel.PUBLIC)
public class TableParameters<K extends Message, V extends Message, M extends Message>{

    // Namespace of the table
    @NonNull
    @Getter
    private final String namespace;

    // Fully qualified table name
    @NonNull
    @Getter
    private final String fullyQualifiedTableName;

    // Key class
    @NonNull
    @Getter
    private final Class<K> kClass;

    // Value class
    @NonNull
    @Getter
    private final Class<V> vClass;

    // Metadata class
    @Getter
    private final Class<M> mClass;

    // Value schema to identify secondary keys
    @NonNull
    @Getter
    private final V valueSchema;

    // Default metadata instance
    @Getter
    private final M metadataSchema;
}