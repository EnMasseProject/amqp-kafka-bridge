/*
 * Copyright 2016, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge;

import io.strimzi.kafka.bridge.config.BridgeConfig;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Base class for source bridge endpoints
 */
public abstract class SourceBridgeEndpoint implements BridgeEndpoint {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected Vertx vertx;

    protected BridgeConfig bridgeConfigProperties;

    private Handler<BridgeEndpoint> closeHandler;

    private KafkaProducer<String, byte[]> producerUnsettledMode;
    private KafkaProducer<String, byte[]> producerSettledMode;

    /**
     * Constructor
     *
     * @param vertx Vert.x instance
     * @param bridgeConfigProperties Bridge configuration
     */
    public SourceBridgeEndpoint(Vertx vertx, BridgeConfig bridgeConfigProperties) {
        this.vertx = vertx;
        this.bridgeConfigProperties = bridgeConfigProperties;
    }

    @Override
    public BridgeEndpoint closeHandler(Handler<BridgeEndpoint> endpointCloseHandler) {
        this.closeHandler = endpointCloseHandler;
        return this;
    }

    /**
     * Raise close event
     */
    protected void handleClose() {

        if (this.closeHandler != null) {
            this.closeHandler.handle(this);
        }
    }

    /**
     * Send a record to Kafka
     *
     * @param krecord   Kafka record to send
     * @param handler   handler to call if producer with unsettled is used
     */
    protected void send(KafkaProducerRecord<String, byte[]> krecord, Handler<AsyncResult<RecordMetadata>> handler) {

        log.debug("Sending record {}", krecord);
        if (handler == null) {
            this.producerSettledMode.write(krecord);
        } else {
            this.producerUnsettledMode.exceptionHandler(e -> {
                handler.handle(Future.failedFuture(e));
            }).send(krecord, handler);
        }
    }

    @Override
    public void open() {

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getProducerConfig().getKeySerializer());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getProducerConfig().getValueSerializer());
        props.put(ProducerConfig.ACKS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getProducerConfig().getAcks());

        this.producerUnsettledMode = KafkaProducer.create(this.vertx, props);

        props.clear();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getProducerConfig().getKeySerializer());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, this.bridgeConfigProperties.getKafkaConfig().getProducerConfig().getValueSerializer());
        props.put(ProducerConfig.ACKS_CONFIG, "0");

        this.producerSettledMode = KafkaProducer.create(this.vertx, props);
    }

    @Override
    public void close() {

        if (this.producerSettledMode != null)
            this.producerSettledMode.close();

        if (this.producerUnsettledMode != null)
            this.producerUnsettledMode.close();
    }
}
