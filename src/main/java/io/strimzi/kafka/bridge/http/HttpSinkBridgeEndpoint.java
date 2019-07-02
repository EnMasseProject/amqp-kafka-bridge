/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge.http;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.strimzi.kafka.bridge.BridgeContentType;
import io.strimzi.kafka.bridge.EmbeddedFormat;
import io.strimzi.kafka.bridge.Endpoint;
import io.strimzi.kafka.bridge.SinkBridgeEndpoint;
import io.strimzi.kafka.bridge.SinkTopicSubscription;
import io.strimzi.kafka.bridge.converter.MessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpBinaryMessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpJsonMessageConverter;
import io.strimzi.kafka.bridge.http.model.HttpBridgeError;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HttpSinkBridgeEndpoint<K, V> extends SinkBridgeEndpoint<K, V> {

    Pattern forwardedHostPattern = Pattern.compile("host=([^;]+)", Pattern.CASE_INSENSITIVE);
    Pattern forwardedProtoPattern = Pattern.compile("proto=([^;]+)", Pattern.CASE_INSENSITIVE);
    Pattern hostPortPattern = Pattern.compile("^.*:[0-9]+$");

    private MessageConverter<K, V, Buffer, Buffer> messageConverter;

    private HttpBridgeContext httpBridgeContext;

    HttpSinkBridgeEndpoint(Vertx vertx, HttpBridgeConfig httpBridgeConfigProperties, HttpBridgeContext context,
                           EmbeddedFormat format, Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer) {
        super(vertx, httpBridgeConfigProperties, format, keyDeserializer, valueDeserializer);
        this.httpBridgeContext = context;
    }

    @Override
    public void open() {

    }

    @Override
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity"})
    public void handle(Endpoint<?> endpoint) {

        RoutingContext routingContext = (RoutingContext) endpoint.get();
        JsonObject bodyAsJson = null;
        // TODO: it seems that getBodyAsJson raises an exception when the body is empty and not null
        try {
            bodyAsJson = routingContext.getBodyAsJson();
        } catch (Exception ex) {

        }
        log.debug("[{}] Request: body = {}", routingContext.get("request-id"), bodyAsJson);

        messageConverter = this.buildMessageConverter();

        switch (this.httpBridgeContext.getOpenApiOperation()) {

            case SUBSCRIBE:
                doSubscribe(routingContext, bodyAsJson);
                break;

            case ASSIGN:
                doAssign(routingContext, bodyAsJson);
                break;

            case POLL:
                doPoll(routingContext);
                break;

            case DELETE_CONSUMER:
                doDeleteConsumer(routingContext);
                break;

            case COMMIT:
                doCommit(routingContext, bodyAsJson);
                break;

            case SEEK:
                doSeek(routingContext, bodyAsJson);
                break;

            case SEEK_TO_BEGINNING:
            case SEEK_TO_END:
                doSeekTo(routingContext, bodyAsJson, this.httpBridgeContext.getOpenApiOperation());
                break;

            case UNSUBSCRIBE:
                doUnsubscribe(routingContext);
                break;

            default:
                throw new IllegalArgumentException("Unknown Operation: " + this.httpBridgeContext.getOpenApiOperation());
        }

    }

    private void doSeek(RoutingContext routingContext, JsonObject bodyAsJson) {
        JsonArray seekOffsetsList = bodyAsJson.getJsonArray("offsets");

        List<Future> seekHandlers = new ArrayList<>(seekOffsetsList.size());
        for (int i = 0; i < seekOffsetsList.size(); i++) {
            TopicPartition topicPartition = new TopicPartition(seekOffsetsList.getJsonObject(i));
            long offset = seekOffsetsList.getJsonObject(i).getLong("offset");
            Future<Void> fut = Future.future();
            seekHandlers.add(fut);
            this.seek(topicPartition, offset, fut.completer());
        }

        CompositeFuture.join(seekHandlers).setHandler(done -> {
            if (done.succeeded()) {
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
            } else {
                HttpResponseStatus statusCode = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                if (done.cause() instanceof IllegalStateException) {
                    statusCode = HttpResponseStatus.NOT_FOUND;
                }
                HttpBridgeError error = new HttpBridgeError(
                        statusCode.code(),
                        done.cause().getMessage()
                );
                HttpUtils.sendResponse(routingContext, statusCode.code(),
                        BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            }
        });
    }

    private void doSeekTo(RoutingContext routingContext, JsonObject bodyAsJson, HttpOpenApiOperations seekToType) {
        JsonArray seekPartitionsList = bodyAsJson.getJsonArray("partitions");

        Set<TopicPartition> set = seekPartitionsList.stream()
                .map(JsonObject.class::cast)
                .map(json -> new TopicPartition(json.getString("topic"), json.getInteger("partition")))
                .collect(Collectors.toSet());

        Handler<AsyncResult<Void>> seekHandler = done -> {
            if (done.succeeded()) {
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
            } else {
                HttpResponseStatus statusCode = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                if (done.cause() instanceof IllegalStateException) {
                    statusCode = HttpResponseStatus.NOT_FOUND;
                }
                HttpBridgeError error = new HttpBridgeError(
                        statusCode.code(),
                        done.cause().getMessage()
                );
                HttpUtils.sendResponse(routingContext, statusCode.code(),
                        BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            }
        };

        if (seekToType == HttpOpenApiOperations.SEEK_TO_BEGINNING) {
            this.seekToBeginning(set, seekHandler);
        } else {
            this.seekToEnd(set, seekHandler);
        }
    }

    private void doCommit(RoutingContext routingContext, JsonObject bodyAsJson) {

        if (bodyAsJson != null) {
            JsonArray offsetsList = bodyAsJson.getJsonArray("offsets");
            Map<TopicPartition, OffsetAndMetadata> offsetData = new HashMap<>();

            for (int i = 0; i < offsetsList.size(); i++) {
                TopicPartition topicPartition = new TopicPartition(offsetsList.getJsonObject(i));
                OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(offsetsList.getJsonObject(i));
                offsetData.put(topicPartition, offsetAndMetadata);
            }
            this.commit(offsetData, status -> {
                if (status.succeeded()) {
                    HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
                } else {
                    HttpBridgeError error = new HttpBridgeError(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            status.cause().getMessage()
                    );
                    HttpUtils.sendResponse(routingContext, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                }
            });
        } else {
            this.commit(status -> {
                if (status.succeeded()) {
                    HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
                } else {
                    HttpBridgeError error = new HttpBridgeError(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            status.cause().getMessage()
                    );
                    HttpUtils.sendResponse(routingContext, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                }
            });
        }
    }

    private void doDeleteConsumer(RoutingContext routingContext) {
        this.close();
        log.info("Deleted consumer {} from group {}", routingContext.pathParam("name"), routingContext.pathParam("groupid"));
        HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
    }

    private void doPoll(RoutingContext routingContext) {
        String accept = routingContext.request().getHeader("Accept");

        // check that the accepted body by the client is the same as the format on creation
        if (accept != null && this.checkAcceptedBody(accept)) {

            if (routingContext.request().getParam("timeout") != null) {
                this.pollTimeOut = Long.parseLong(routingContext.request().getParam("timeout"));
            }

            if (routingContext.request().getParam("max_bytes") != null) {
                this.maxBytes = Long.parseLong(routingContext.request().getParam("max_bytes"));
            }

            this.consume(records -> {
                if (records.succeeded()) {
                    Buffer buffer = messageConverter.toMessages(records.result());
                    if (buffer.getBytes().length > this.maxBytes) {
                        HttpBridgeError error = new HttpBridgeError(
                                HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                                "Response exceeds the maximum number of bytes the consumer can receive"
                        );
                        HttpUtils.sendResponse(routingContext, HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                                BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                    } else {
                        HttpUtils.sendResponse(routingContext, HttpResponseStatus.OK.code(),
                                this.format == EmbeddedFormat.BINARY ? BridgeContentType.KAFKA_JSON_BINARY : BridgeContentType.KAFKA_JSON_JSON,
                                buffer);
                    }

                } else {
                    HttpBridgeError error = new HttpBridgeError(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            records.cause().getMessage()
                    );
                    HttpUtils.sendResponse(routingContext, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                }
            });
        } else {
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.NOT_ACCEPTABLE.code(),
                    "Consumer format does not match the embedded format requested by the Accept header."
            );
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.NOT_ACCEPTABLE.code(),
                    BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
        }
    }

    private void doAssign(RoutingContext routingContext, JsonObject bodyAsJson) {
        JsonArray partitionsList = bodyAsJson.getJsonArray("partitions");
        this.topicSubscriptions.addAll(
                partitionsList.stream()
                        .map(JsonObject.class::cast)
                        .map(json -> new SinkTopicSubscription(json.getString("topic"), json.getInteger("partition"), json.getLong("offset")))
                        .collect(Collectors.toList())
        );

        this.setAssignHandler(assignResult -> {
            if (assignResult.succeeded()) {
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
            }
        });

        this.assign(false);
    }

    private void doSubscribe(RoutingContext routingContext, JsonObject bodyAsJson) {
        // cannot specify both topics list and topic pattern
        if (bodyAsJson.containsKey("topics") && bodyAsJson.containsKey("topic_pattern")) {
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.CONFLICT.code(),
                    "Subscriptions to topics, partitions, and patterns are mutually exclusive."
            );
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.CONFLICT.code(),
                    BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            return;
        }

        // one of topics list or topic pattern has to be specified
        if (!bodyAsJson.containsKey("topics") && !bodyAsJson.containsKey("topic_pattern")) {
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                    "A list (of Topics type) or a topic_pattern must be specified."
            );
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                    BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            return;
        }

        this.setSubscribeHandler(subscribeResult -> {
            if (subscribeResult.succeeded()) {
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
            }
        });

        if (bodyAsJson.containsKey("topics")) {
            JsonArray topicsList = bodyAsJson.getJsonArray("topics");
            this.topicSubscriptions.addAll(
                topicsList.stream()
                        .map(String.class::cast)
                        .map(topic -> new SinkTopicSubscription(topic))
                        .collect(Collectors.toList())
            );
            this.subscribe(false);
        } else if (bodyAsJson.containsKey("topic_pattern")) {
            Pattern pattern = Pattern.compile(bodyAsJson.getString("topic_pattern"));
            this.subscribe(pattern, false);
        }
    }

    public void doUnsubscribe(RoutingContext routingContext) {
        this.setUnsubscribeHandler(unsubscribeResult -> {
            if (unsubscribeResult.succeeded()) {
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.NO_CONTENT.code(), null, null);
            } else {
                HttpBridgeError error = new HttpBridgeError(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                        unsubscribeResult.cause().getMessage()
                );
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                        BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            }
        });
        this.unsubscribe();
    }

    /**
     * Add a configuration parameter with key and value to the provided Properties bag
     *
     * @param key key of the configuration parameter
     * @param value value of the configuration parameter
     * @param props Properties bag where to put the configuration parameter
     */
    private void addConfigParameter(String key, String value, Properties props) {
        if (value != null) {
            props.put(key, value);
        }
    }

    @Override
    public void handle(Endpoint<?> endpoint, Handler<?> handler) {
        RoutingContext routingContext = (RoutingContext) endpoint.get();
        JsonObject bodyAsJson = routingContext.getBodyAsJson();
        log.debug("[{}] Request: body = {}", routingContext.get("request-id"), bodyAsJson);

        switch (this.httpBridgeContext.getOpenApiOperation()) {

            case CREATE_CONSUMER:

                // get the consumer group-id
                groupId = routingContext.pathParam("groupid");

                JsonObject json = bodyAsJson;
                // if no name, a random one is assigned
                this.name = json.getString("name", "kafka-bridge-consumer-" + UUID.randomUUID());

                if (this.httpBridgeContext.getHttpSinkEndpoints().containsKey(this.name)) {
                    HttpBridgeError error = new HttpBridgeError(
                            HttpResponseStatus.CONFLICT.code(),
                            "A consumer instance with the specified name already exists in the Kafka Bridge."
                    );
                    HttpUtils.sendResponse(routingContext, HttpResponseStatus.CONFLICT.code(),
                            BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                    return;
                }

                // construct base URI for consumer
                String requestUri = this.buildRequestUri(routingContext);
                if (!routingContext.request().path().endsWith("/")) {
                    requestUri += "/";
                }
                String consumerBaseUri = requestUri + "instances/" + this.name;

                // get supported consumer configuration parameters
                Properties config = new Properties();
                addConfigParameter(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        json.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, null), config);
                addConfigParameter(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        json.getString(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, null), config);
                addConfigParameter(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,
                        json.getString(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, null), config);
                addConfigParameter(ConsumerConfig.CLIENT_ID_CONFIG, this.name, config);

                // create the consumer
                this.initConsumer(false, config);

                ((Handler<String>) handler).handle(this.name);

                log.info("Created consumer {} in group {}", this.name, groupId);
                // send consumer instance id(name) and base URI as response
                JsonObject body = new JsonObject()
                        .put("instance_id", this.name)
                        .put("base_uri", consumerBaseUri);
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.OK.code(),
                        BridgeContentType.KAFKA_JSON, body.toBuffer());
                break;

            default:
                throw new IllegalArgumentException("Unknown Operation: " + this.httpBridgeContext.getOpenApiOperation());
        }
    }

    private MessageConverter<K, V, Buffer, Buffer> buildMessageConverter() {
        switch (this.format) {
            case JSON:
                return (MessageConverter<K, V, Buffer, Buffer>) new HttpJsonMessageConverter();
            case BINARY:
                return (MessageConverter<K, V, Buffer, Buffer>) new HttpBinaryMessageConverter();
        }
        return null;
    }

    private boolean checkAcceptedBody(String accept) {
        switch (accept) {
            case BridgeContentType.KAFKA_JSON_JSON:
                return format == EmbeddedFormat.JSON;
            case BridgeContentType.KAFKA_JSON_BINARY:
                return format == EmbeddedFormat.BINARY;
        }
        return false;
    }

    /**
     * Build the request URI for the future consumer requests
     *
     * @param routingContext context of the current HTTP request
     * @return the request URI for the future consumer requests
     */
    private String buildRequestUri(RoutingContext routingContext) {
        String baseUri = null;

        String forwarded = routingContext.request().getHeader("forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            Matcher hostMatcher = forwardedHostPattern.matcher(forwarded);
            Matcher protoMatcher = forwardedProtoPattern.matcher(forwarded);
            if (hostMatcher.find() && protoMatcher.find()) {
                log.debug("Getting base URI from forwarded: {} HTTP header", forwarded);
                baseUri = this.buildBaseUri(routingContext, hostMatcher.group(1), protoMatcher.group(1));
            }
        } else {
            String xForwardedHost = routingContext.request().getHeader("x-forwarded-host");
            String xForwardedProto = routingContext.request().getHeader("x-forwarded-proto");
            if (xForwardedHost != null && !xForwardedHost.isEmpty() &&
                xForwardedProto != null && !xForwardedProto.isEmpty()) {
                log.debug("Getting base URI from x-forwarded-host: {} and x-forwarded-proto: {} HTTP headers",
                        xForwardedHost, xForwardedProto);
                baseUri = this.buildBaseUri(routingContext, xForwardedHost, xForwardedProto);
            }
        }

        // no forwarded headers specified, just use the host one
        if (baseUri == null) {
            log.debug("Getting base URI from host: {} HTTP header", routingContext.request().host());
            baseUri = routingContext.request().absoluteURI();
        }
        return baseUri;
    }

    private String buildBaseUri(RoutingContext routingContext, String host, String proto) {
        if (!host.matches(hostPortPattern.pattern())) {
            int port;
            if (proto.equals("http")) {
                port = 80;
            } else if (proto.equals("https")) {
                port = 443;
            } else {
                throw new IllegalArgumentException(proto + " is not a valid schema/proto.");
            }
            return String.format("%s://%s", proto, host + ":" + port) + routingContext.request().path();
        }
        return String.format("%s://%s", proto, host) + routingContext.request().path();
    }
}
