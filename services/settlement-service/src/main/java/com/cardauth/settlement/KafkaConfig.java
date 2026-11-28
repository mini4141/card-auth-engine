package com.cardauth.settlement;

import com.cardauth.domain.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /** Poison messages go to card.authorizations.dlq after 3 retries. */
    @Bean
    CommonErrorHandler errorHandler(KafkaOperations<String, String> ops) {
        var recoverer = new DeadLetterPublishingRecoverer(ops,
                (rec, ex) -> new org.apache.kafka.common.TopicPartition(Topics.AUTHORIZATIONS_DLQ, -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(200, 3));
    }

    // Same topics as auth-service declares (idempotent), so start-up order does not matter.
    @Bean NewTopic authorizationsTopic(@Value("${app.kafka.partitions:6}") int p, @Value("${app.kafka.replicas:1}") int r) {
        return TopicBuilder.name(Topics.AUTHORIZATIONS).partitions(p).replicas(r).build();
    }
    @Bean NewTopic dlqTopic(@Value("${app.kafka.partitions:6}") int p, @Value("${app.kafka.replicas:1}") int r) {
        return TopicBuilder.name(Topics.AUTHORIZATIONS_DLQ).partitions(p).replicas(r).build();
    }
    @Bean NewTopic settlementsTopic(@Value("${app.kafka.partitions:6}") int p, @Value("${app.kafka.replicas:1}") int r) {
        return TopicBuilder.name(Topics.SETTLEMENTS).partitions(p).replicas(r).build();
    }
}
