package com.xyblue.itemnest.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "itemnest.rabbitmq.worker-enabled", havingValue = "true")
public class MqWorkerRunner implements ApplicationRunner {
    private final RabbitMqService mq;
    private final ObjectMapper objectMapper;
    private final Path eventLog;

    public MqWorkerRunner(RabbitMqService mq, ObjectMapper objectMapper, Path itemNestDataDir) {
        this.mq = mq;
        this.objectMapper = objectMapper;
        this.eventLog = itemNestDataDir.resolve("mq_events.jsonl");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!mq.enabled()) {
            System.out.println("[RabbitMQ Worker] RabbitMQ 已禁用，Worker 退出。");
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                consumeForever();
            } catch (Exception ex) {
                System.out.println("[RabbitMQ Worker] disconnected: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "; retry in 3s");
                Thread.sleep(3000);
            }
        }
    }

    private void consumeForever() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(mq.url());
        factory.setConnectionTimeout(3000);
        factory.setAutomaticRecoveryEnabled(false);

        try (Connection connection = factory.newConnection("ItemNest Worker");
             Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(mq.exchange(), "topic", true);
            channel.queueDeclare(mq.queue(), true, false, false, null);
            channel.queueBind(mq.queue(), mq.exchange(), "inventory.#");
            channel.basicQos(16);
            System.out.println("[RabbitMQ Worker] consuming queue=" + mq.queue() + "; Ctrl+C to stop");

            CountDownLatch latch = new CountDownLatch(1);
            channel.basicConsume(mq.queue(), false, (consumerTag, delivery) -> {
                try {
                    Map<String, Object> event = objectMapper.readValue(delivery.getBody(), new TypeReference<>() {});
                    event.put("consumed_at", Instant.now().toString());
                    Files.writeString(eventLog, objectMapper.writeValueAsString(event) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    System.out.println("[RabbitMQ Worker] " + event.get("event") + " " + event.get("event_id"));
                } catch (Exception ex) {
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                }
            }, consumerTag -> latch.countDown());
            latch.await();
        }
    }
}
