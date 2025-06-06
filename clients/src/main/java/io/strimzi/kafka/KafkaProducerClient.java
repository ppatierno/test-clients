/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka;

import io.apicurio.registry.serde.jsonschema.JsonSchemaKafkaSerializer;
import io.skodjob.datagenerator.DataGenerator;
import io.skodjob.datagenerator.enums.ETemplateType;
import io.strimzi.common.ClientsInterface;
import io.strimzi.common.properties.KafkaProperties;
import io.strimzi.configuration.ConfigurationConstants;
import io.strimzi.configuration.kafka.KafkaProducerConfiguration;
import io.strimzi.test.tracing.TracingUtil;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KafkaProducerClient implements ClientsInterface {

    private static final Logger LOGGER = LogManager.getLogger(KafkaProducerClient.class);
    private final KafkaProducerConfiguration configuration;
    private final KafkaProducer<Object, Object> producer;
    private int messageIndex;
    private int messageSuccessfullySent;
    private final ScheduledExecutorService scheduledExecutor;
    private final CountDownLatch countDownLatch;
    private DataGenerator dataGenerator;

    public KafkaProducerClient(Map<String, String> configuration) {
        this.configuration = new KafkaProducerConfiguration(configuration);
        Properties properties = KafkaProperties.producerProperties(this.configuration);
        TracingUtil.getTracing().addTracingPropsToProducerConfig(properties);

        this.producer = new KafkaProducer<>(properties);
        this.messageIndex = 0;
        this.messageSuccessfullySent = 0;
        this.scheduledExecutor = Executors.newScheduledThreadPool(1, r -> new Thread(r, "kafka-producer"));
        this.countDownLatch  = new CountDownLatch(1);
        // If template is set, generate data based on it
        if (this.configuration.getMessageTemplate() != null) {
            dataGenerator = new DataGenerator(ETemplateType.getFromString(this.configuration.getMessageTemplate()));
        }
    }

    @Override
    public void run() {
        LOGGER.info("Starting {} with configuration: \n{}", this.getClass().getName(), configuration);

        if (configuration.isTransactionalProducer()) {
            LOGGER.info("Using transactional producer. Initializing the transactions.");
            producer.initTransactions();
        }

        // in case we want to send all messages immediately, we have to schedule just one task
        if (configuration.getDelayMs() == 0) {
            sendMessages();
        } else {
            scheduledExecutor.scheduleAtFixedRate(this::checkAndSendMessages, ConfigurationConstants.DEFAULT_DELAY_MS, configuration.getDelayMs(), TimeUnit.MILLISECONDS);
            awaitCompletion();
        }

        checkFinalState();
    }

    @Override
    public void awaitCompletion() {
        try {
            countDownLatch.await();
            scheduledExecutor.awaitTermination(ConfigurationConstants.DEFAULT_TASK_COMPLETION_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Failed to wait for task completion due to: {}", e.getMessage());
            e.printStackTrace();
        } finally {
            if (!scheduledExecutor.isShutdown()) {
                scheduledExecutor.shutdownNow();
            }
        }
    }

    @Override
    public void checkFinalState() {
        if (messageSuccessfullySent == configuration.getMessageCount()) {
            LOGGER.info("All messages successfully sent");
        } else {
            LOGGER.error("Unable to correctly send all messages");
            throw new RuntimeException("Failed to send all messages");
        }
    }

    public void checkAndSendMessages() {
        if (messageIndex == configuration.getMessageCount()) {
            LOGGER.info("Shutting down the executor");
            scheduledExecutor.shutdown();
            countDownLatch.countDown();
        } else {
            try {
                this.sendMessages();
            } catch (Exception e) {
                LOGGER.error("Caught exception: {}", e.getMessage());
                e.printStackTrace();
                scheduledExecutor.shutdown();
                countDownLatch.countDown();
            }
        }
    }

    public ProducerRecord generateMessage(int numOfMessage) {
        Object message;

        if (this.configuration.getMessageTemplate() != null) {
            if (this.configuration.getValueSerializer().contains(StringSerializer.class.getName())) {
                // Convert generated data to String
                message = dataGenerator.generateData().toString();
            }  else if (this.configuration.getValueSerializer().equals(ByteArraySerializer.class.getName())) {
                // Convert generated data to bytes
                message = dataGenerator.generateData().toString().getBytes(StandardCharsets.UTF_8);
            }  else if (this.configuration.getValueSerializer().equals(JsonSchemaKafkaSerializer.class.getName())) {
                // JSON message
                message = dataGenerator.generateJsonData();
            }  else {
                message = dataGenerator.generateData();
            }
        } else {
            message = configuration.getMessage() + " - " + numOfMessage;
        }

        return new ProducerRecord(configuration.getTopicName(), null, null, configuration.getMessageKey(),
            message, configuration.getHeaders());
    }

    public List<ProducerRecord> generateMessages() {
        List<ProducerRecord> records = new ArrayList<>();
        for (int i = 0; i < configuration.getMessageCount(); i++) {
            records.add(generateMessage(i));
        }

        return records;
    }

    public void sendMessages() {
        List<ProducerRecord> records = configuration.getDelayMs() == 0 ? generateMessages() : Collections.singletonList(generateMessage(messageIndex));

        int currentMsgIndex = configuration.getDelayMs() == 0 ? 0 : messageIndex;

        for (ProducerRecord record : records) {

            if (configuration.isTransactionalProducer() && currentMsgIndex % configuration.getMessagesPerTransaction() == 0) {
                LOGGER.info("Beginning new transaction. Messages sent: {}", currentMsgIndex);
                producer.beginTransaction();
            }
            LOGGER.info("Sending message: {}", record);

            try {
                producer.send(record).get();
                messageSuccessfullySent++;
            } catch (Exception e) {
                LOGGER.error("Failed to send messages: {} due to: \n{}", record, e.getMessage());
            } finally {
                LOGGER.info("Messages sent: {}", currentMsgIndex);
                messageIndex++;
                currentMsgIndex++;
            }

            if (configuration.isTransactionalProducer() && (currentMsgIndex + 1) % configuration.getMessagesPerTransaction() == 0) {
                LOGGER.info("Committing the transaction for message {}", currentMsgIndex);
                producer.commitTransaction();
            }
        }
    }
}
