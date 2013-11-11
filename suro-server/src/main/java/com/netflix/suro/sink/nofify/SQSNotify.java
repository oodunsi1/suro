/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.suro.sink.nofify;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.*;
import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.suro.TagKey;
import com.netflix.suro.sink.notify.Notify;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class SQSNotify implements Notify<String> {
    static Logger log = LoggerFactory.getLogger(SQSNotify.class);

    public static final String TYPE = "sqs";

    private final List<String> queues;
    private final List<String> queueUrls = new ArrayList<String>();

    private AmazonSQSClient sqsClient;
    private final AWSCredentialsProvider credentialsProvider;

    private ClientConfiguration clientConfig;
    private final String region;

    @Monitor(name = TagKey.SENT_COUNT, type = DataSourceType.COUNTER)
    private AtomicLong sentMessageCount = new AtomicLong(0);
    @Monitor(name = TagKey.LOST_COUNT, type = DataSourceType.COUNTER)
    private AtomicLong lostMessageCount = new AtomicLong(0);
    @Monitor(name = TagKey.RECV_COUNT, type = DataSourceType.COUNTER)
    private AtomicLong recvMessageCount = new AtomicLong(0);

    @JsonCreator
    public SQSNotify(
            @JsonProperty("queues") List<String> queues,
            @JsonProperty("region") @JacksonInject("region") String region,
            @JsonProperty("connectionTimeout") int connectionTimeout,
            @JsonProperty("maxConnections") int maxConnections,
            @JsonProperty("socketTimeout") int socketTimeout,
            @JsonProperty("maxRetries") int maxRetries,
            @JacksonInject("sqsClient") AmazonSQSClient sqsClient,
            @JacksonInject("credentials") AWSCredentialsProvider credentialsProvider) {
        this.queues = queues;
        this.region = region;

        this.sqsClient = sqsClient;
        this.credentialsProvider = credentialsProvider;

        Preconditions.checkArgument(queues.size() > 0);
        Preconditions.checkNotNull(region);

        clientConfig = new ClientConfiguration();
        if (connectionTimeout > 0) {
            clientConfig = clientConfig.withConnectionTimeout(connectionTimeout);
        }
        if (maxConnections > 0) {
            clientConfig = clientConfig.withMaxConnections(maxConnections);
        }
        if (socketTimeout > 0) {
            clientConfig = clientConfig.withSocketTimeout(socketTimeout);
        }
        if (maxRetries > 0) {
            clientConfig = clientConfig.withMaxErrorRetry(maxRetries);
        }
    }

    @Override
    public void init() {
        if (sqsClient == null) { // not injected
            sqsClient = new AmazonSQSClient(credentialsProvider, clientConfig);
        }
        String endpoint = "sqs." + this.region + ".amazonaws.com";
        sqsClient.setEndpoint(endpoint);

        for (String queueName : queues) {
            GetQueueUrlRequest request = new GetQueueUrlRequest();
            request.setQueueName(queueName);
            queueUrls.add(sqsClient.getQueueUrl(request).getQueueUrl());
        }

        log.info(String.format("SQSNotify initialized with the endpoint: %s, queue: %s",
                endpoint, queues));
    }

    @Override
    public boolean send(String message) {
        boolean sent = false;

        try {
            for (String queueUrl : queueUrls) {
                SendMessageRequest request = new SendMessageRequest()
                        .withQueueUrl(queueUrl)
                        .withMessageBody(message);
                sqsClient.sendMessage(request);
                if (sent == false) {
                    sentMessageCount.incrementAndGet();
                    sent = true;
                }
            }
        } catch (Exception e) {
            log.error("Exception while sending SQS notification: " + e.getMessage(), e);
        }

        if (sent == false) {
            lostMessageCount.incrementAndGet();
        }

        return sent;
    }

    @Override
    public String recv() {
        ReceiveMessageRequest request = new ReceiveMessageRequest()
                .withQueueUrl(queueUrls.get(0))
                .withMaxNumberOfMessages(1);

        try {
            ReceiveMessageResult result = sqsClient.receiveMessage(request);
            if (result.getMessages().isEmpty() == false) {
                Message msg = result.getMessages().get(0);

                recvMessageCount.incrementAndGet();

                DeleteMessageRequest deleteReq = new DeleteMessageRequest()
                        .withQueueUrl(queueUrls.get(0))
                        .withReceiptHandle(msg.getReceiptHandle());
                sqsClient.deleteMessage(deleteReq);

                return msg.getBody();
            } else {
                return "";
            }
        } catch (Exception e) {
            log.error("Exception while recving SQS notification: " + e.getMessage(), e);
            return "";
        }
    }

    @Override
    public String getStat() {
        return String.format("SQSNotify with the queues: %s, sent : %d, received: %d, dropped: %d",
                queues, sentMessageCount.get(), recvMessageCount.get(), lostMessageCount.get());
    }
}