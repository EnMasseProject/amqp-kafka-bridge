/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.bridge.clients;

import io.vertx.core.Vertx;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

public class Producer extends ClientHandlerBase<Integer> implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(Producer.class);
    private Properties properties;
    private final AtomicInteger numSent = new AtomicInteger(0);
    private final String topic;
    private final String clientName;
    private final String message;
    private final int partition;
    private final boolean withNullKeyRecord;

    public Producer(Properties properties, CompletableFuture<Integer> resultPromise, IntPredicate msgCntPredicate,
                    String topic, String message, int partition, boolean withNullKeyRecord) {
        super(resultPromise, msgCntPredicate);
        this.topic = topic;
        this.clientName = "producer-sender-plain-";
        this.properties = properties;
        this.message = message;
        this.partition = partition;
        this.withNullKeyRecord = withNullKeyRecord;
        this.vertx = Vertx.vertx();
    }

    public Producer(Properties properties, CompletableFuture<Integer> resultPromise, IntPredicate msgCntPredicate,
                    String topic, String message, int partition) {
        super(resultPromise, msgCntPredicate);
        this.topic = topic;
        this.clientName = "producer-sender-plain-";
        this.properties = properties;
        this.message = message;
        this.partition = partition;
        this.withNullKeyRecord = false;
        this.vertx = Vertx.vertx();
    }

    public Producer(CompletableFuture<Integer> resultPromise, IntPredicate msgCntPredicate, String topic, String message,
                    int partition, boolean withNullKeyRecord) {
        super(resultPromise, msgCntPredicate);
        this.topic = topic;
        this.clientName = "producer-sender-plain-";
        this.properties = fillDefaultProperties();
        this.message = message;
        this.partition = partition;
        this.withNullKeyRecord = withNullKeyRecord;
        this.vertx = Vertx.vertx();
    }

    @Override
    protected void handleClient() {
        LOGGER.info("Creating instance of Vert.x for the client " + this.getClass().getName());

        LOGGER.info("Producer is starting with following properties: " + properties.toString());

        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, properties);

        if (msgCntPredicate.test(-1)) {
            vertx.eventBus().consumer(clientName, msg -> {
                if (msg.body().equals("stop")) {
                    LOGGER.info("Received stop command! Produced messages: " + numSent.get());
                    resultPromise.complete(numSent.get());
                }
            });
            vertx.setPeriodic(1000, id -> sendNext(producer, topic, message, partition, withNullKeyRecord));
        } else {
            sendNext(producer, topic, message, partition, withNullKeyRecord);
        }
    }

    @Override
    public void close() {
        LOGGER.info("Closing Vert.x instance for the client {}" +  this.getClass().getName());
        if (vertx != null) {
            vertx.close();
        }
    }

    private void sendNext(KafkaProducer<String, String> producer, String topic, String message, int partition, boolean withNullKeyRecord) {
        if (msgCntPredicate.negate().test(numSent.get())) {

            KafkaProducerRecord<String, String> record;

            if (withNullKeyRecord) {
                record = KafkaProducerRecord.create(topic, null, message, partition);
            } else {
                record = KafkaProducerRecord.create(topic, "key", message, partition);
            }

            producer.send(record, done -> {
                if (done.succeeded()) {
                    RecordMetadata recordMetadata = done.result();
                    LOGGER.info("Message " + record.value() + " written on topic=" + recordMetadata.getTopic() +
                        ", partition=" + recordMetadata.getPartition() +
                        ", offset=" + recordMetadata.getOffset());

                    numSent.getAndIncrement();

                    if (msgCntPredicate.test(numSent.get())) {
                        LOGGER.info("Producer produced " + numSent.get() + " messages");
                        resultPromise.complete(numSent.get());
                    }

                    if (msgCntPredicate.negate().test(-1)) {
                        sendNext(producer, topic, message, partition, withNullKeyRecord);
                    }

                } else {
                    LOGGER.error("Producer cannot connect to topic " + topic + ":" + done.cause().toString());
                    sendNext(producer, topic, message, partition, withNullKeyRecord);
                }
            });

        }
    }

    private Properties fillDefaultProperties() {
        Properties properties = new Properties();

        properties.setProperty("key.serializer", StringSerializer.class.getName());
        properties.setProperty("value.serializer", StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, this.clientName);
        properties.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name);

        return properties;
    }

    public Properties getProperties() {
        return properties;
    }
}
