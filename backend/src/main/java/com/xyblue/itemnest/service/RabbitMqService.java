package com.xyblue.itemnest.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class RabbitMqService {
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String url;
    private final String exchange;
    private final String queue;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String lastError = "";

    public RabbitMqService(
        ObjectMapper objectMapper,
        @Value("${itemnest.rabbitmq.enabled:true}") boolean enabled,
        @Value("${itemnest.rabbitmq.url:amqp://guest:guest@127.0.0.1/}") String url,
        @Value("${itemnest.rabbitmq.exchange:itemnest.events}") String exchange,
        @Value("${itemnest.rabbitmq.queue:itemnest.inventory.events}") String queue
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.url = url;
        this.exchange = exchange;
        this.queue = queue;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (!enabled) return;
        try (Connection connection = newConnection()) {
            connected.set(connection.isOpen());
            lastError = "";
        } catch (Exception ex) {
            connected.set(false);
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    @Async
    public void publishEvent(String event, Map<String, Object> payload) {
        if (!enabled) return;
        Map<String, Object> envelope = new LinkedHashMap<>();
        String eventId = UUID.randomUUID().toString();
        envelope.put("event_id", eventId);
        envelope.put("event", event);
        envelope.put("source", "itemnest-api");
        envelope.put("created_at", Instant.now().toString());
        envelope.put("payload", payload);

        try (Connection connection = newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(exchange, "topic", true);
            channel.queueDeclare(queue, true, false, false, null);
            channel.queueBind(queue, exchange, "inventory.#");
            byte[] body = objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .messageId(eventId)
                .timestamp(java.util.Date.from(Instant.now()))
                .build();
            channel.basicPublish(exchange, event, properties, body);
            connected.set(true);
            lastError = "";
        } catch (Exception ex) {
            connected.set(false);
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("connected", enabled && connected.get());
        status.put("url", safeUrl());
        status.put("exchange", exchange);
        status.put("queue", queue);
        status.put("last_error", lastError);
        status.put("client", "rabbitmq-java-client");
        return status;
    }

    Connection newConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(url);
        factory.setConnectionTimeout(2000);
        factory.setHandshakeTimeout(2000);
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection("ItemNest");
    }

    public String exchange() { return exchange; }
    public String queue() { return queue; }
    public boolean enabled() { return enabled; }
    public String url() { return url; }

    private String safeUrl() {
        try {
            URI uri = URI.create(url);
            String userInfo = uri.getUserInfo();
            String safeUser = "";
            if (userInfo != null && !userInfo.isBlank()) {
                String user = userInfo.split(":", 2)[0];
                safeUser = user + ":***@";
            }
            int port = uri.getPort();
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            return uri.getScheme() + "://" + safeUser + host + (port > 0 ? ":" + port : "") + (uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath());
        } catch (Exception ex) {
            return "amqp://***";
        }
    }
}
