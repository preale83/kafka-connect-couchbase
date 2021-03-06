/*
 * Copyright (c) 2017 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.connect.kafka;

import com.couchbase.client.core.time.Delay;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.PersistTo;
import com.couchbase.client.java.ReplicateTo;
import com.couchbase.client.java.document.Document;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.env.CouchbaseEnvironment;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.couchbase.client.java.error.DocumentDoesNotExistException;
import com.couchbase.client.java.transcoder.Transcoder;
import com.couchbase.client.java.util.retry.RetryBuilder;
import com.couchbase.connect.kafka.util.DocumentIdExtractor;
import com.couchbase.connect.kafka.util.JsonBinaryDocument;
import com.couchbase.connect.kafka.util.JsonBinaryTranscoder;
import com.couchbase.connect.kafka.util.Version;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Observable;
import rx.functions.Func1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.couchbase.client.deps.io.netty.util.CharsetUtil.UTF_8;
import static com.couchbase.connect.kafka.CouchbaseSinkConnectorConfig.DOCUMENT_ID_POINTER_CONFIG;
import static com.couchbase.connect.kafka.CouchbaseSinkConnectorConfig.PERSIST_TO_CONFIG;
import static com.couchbase.connect.kafka.CouchbaseSinkConnectorConfig.REMOVE_DOCUMENT_ID_CONFIG;
import static com.couchbase.connect.kafka.CouchbaseSinkConnectorConfig.REPLICATE_TO_CONFIG;

public class CouchbaseSinkTask extends SinkTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseSinkTask.class);

    private Map<String, String> configProperties;
    private CouchbaseSinkTaskConfig config;
    private Bucket bucket;
    private CouchbaseCluster cluster;
    private JsonConverter converter;
    private DocumentIdExtractor documentIdExtractor;
    private PersistTo persistTo;
    private ReplicateTo replicateTo;

    @Override
    public String version() {
        return Version.getVersion();
    }

    @Override
    public void start(Map<String, String> properties) {
        try {
            configProperties = properties;
            config = new CouchbaseSinkTaskConfig(configProperties);
        } catch (ConfigException e) {
            throw new ConnectException("Couldn't start CouchbaseSinkTask due to configuration error", e);
        }

        List<String> clusterAddress = config.getList(CouchbaseSourceConnectorConfig.CONNECTION_CLUSTER_ADDRESS_CONFIG);
        String bucketName = config.getString(CouchbaseSourceConnectorConfig.CONNECTION_BUCKET_CONFIG);
        String username = config.getUsername();
        String password = config.getPassword(CouchbaseSourceConnectorConfig.CONNECTION_PASSWORD_CONFIG).value();

        boolean sslEnabled = config.getBoolean(CouchbaseSourceConnectorConfig.CONNECTION_SSL_ENABLED_CONFIG);
        String sslKeystoreLocation = config.getString(CouchbaseSourceConnectorConfig.CONNECTION_SSL_KEYSTORE_LOCATION_CONFIG);
        String sslKeystorePassword = config.getPassword(CouchbaseSourceConnectorConfig.CONNECTION_SSL_KEYSTORE_PASSWORD_CONFIG).value();
        Long connectTimeout = config.getLong(CouchbaseSourceConnectorConfig.CONNECTION_TIMEOUT_MS_CONFIG);
        CouchbaseEnvironment env = DefaultCouchbaseEnvironment.builder()
                .sslEnabled(sslEnabled)
                .sslKeystoreFile(sslKeystoreLocation)
                .sslKeystorePassword(sslKeystorePassword)
                .connectTimeout(connectTimeout)
                .build();
        cluster = CouchbaseCluster.create(env, clusterAddress);
        cluster.authenticate(username, password);

        List<Transcoder<? extends Document, ?>> transcoders =
                Collections.<Transcoder<? extends Document, ?>>singletonList(new JsonBinaryTranscoder());
        bucket = cluster.openBucket(bucketName, transcoders);

        converter = new JsonConverter();
        converter.configure(Collections.singletonMap("schemas.enable", false), false);

        String docIdPointer = config.getString(DOCUMENT_ID_POINTER_CONFIG);
        if (docIdPointer != null && !docIdPointer.isEmpty()) {
            documentIdExtractor = new DocumentIdExtractor(docIdPointer, config.getBoolean(REMOVE_DOCUMENT_ID_CONFIG));
        }

        persistTo = config.getEnum(PersistTo.class, PERSIST_TO_CONFIG);
        replicateTo = config.getEnum(ReplicateTo.class, REPLICATE_TO_CONFIG);
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        final SinkRecord first = records.iterator().next();
        final int recordsCount = records.size();
        LOGGER.trace("Received {} records. First record kafka coordinates:({}-{}-{}). Writing them to the Couchbase...",
                recordsCount, first.topic(), first.kafkaPartition(), first.kafkaOffset());

        //noinspection unchecked
        Observable.from(records)
                .flatMapCompletable(new Func1<SinkRecord, Completable>() {
                    @Override
                    public Completable call(SinkRecord record) {
                        if (record.value() == null) {
                            String documentId = documentIdFromKafkaMetadata(record);
                            return removeIfExists(documentId);
                        }
                        return bucket.async().upsert(convert(record), persistTo, replicateTo).toCompletable();
                    }
                })
                .retryWhen(
                        // TODO: make it configurable
                        RetryBuilder
                                .anyOf(RuntimeException.class)
                                .delay(Delay.exponential(TimeUnit.SECONDS, 5))
                                .max(5)
                                .build())

                .toCompletable().await();
    }

    private Completable removeIfExists(String documentId) {
        return bucket.async().remove(documentId, persistTo, replicateTo)
                .onErrorResumeNext(new Func1<Throwable, Observable<JsonDocument>>() {
                    @Override
                    public Observable<JsonDocument> call(Throwable throwable) {
                        return (throwable instanceof DocumentDoesNotExistException)
                                ? Observable.<JsonDocument>empty()
                                : Observable.<JsonDocument>error(throwable);
                    }
                }).toCompletable();
    }

    private static String toString(ByteBuffer byteBuffer) {
        final ByteBuffer sliced = byteBuffer.slice();
        byte[] bytes = new byte[sliced.remaining()];
        sliced.get(bytes);
        return new String(bytes, UTF_8);
    }

    private static String documentIdFromKafkaMetadata(SinkRecord record) {
        Object key = record.key();

        if (key instanceof String
                || key instanceof Number
                || key instanceof Boolean) {
            return key.toString();
        }

        if (key instanceof byte[]) {
            return new String((byte[]) key, UTF_8);
        }

        if (key instanceof ByteBuffer) {
            return toString((ByteBuffer) key);
        }

        return record.topic() + "/" + record.kafkaPartition() + "/" + record.kafkaOffset();
    }

    private Document convert(SinkRecord record) {
        byte[] valueAsJsonBytes = converter.fromConnectData(record.topic(), record.valueSchema(), record.value());
        String defaultId = null;

        try {
            if (documentIdExtractor != null) {
                return documentIdExtractor.extractDocumentId(valueAsJsonBytes);
            }

        } catch (DocumentIdExtractor.DocumentIdNotFoundException e) {
            defaultId = documentIdFromKafkaMetadata(record);
            LOGGER.warn(e.getMessage() + "; using fallback ID '{}'", defaultId);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (defaultId == null) {
            defaultId = documentIdFromKafkaMetadata(record);
        }

        return JsonBinaryDocument.create(defaultId, valueAsJsonBytes);
    }

    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
    }

    @Override
    public void stop() {
        cluster.disconnect();
    }
}
