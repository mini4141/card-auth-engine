package com.cardauth.auth.config;

import com.cardauth.domain.Topics;
import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import java.net.URI;
import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

@Configuration
public class InfraConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redisson(@Value("${spring.data.redis.host}") String host,
                            @Value("${spring.data.redis.port}") int port) {
        Config c = new Config();
        c.useSingleServer().setAddress("redis://" + host + ":" + port).setConnectionPoolSize(32).setConnectionMinimumIdleSize(2);
        return Redisson.create(c);
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(AppProperties props) {
        var b = DynamoDbClient.builder().region(Region.of(props.dynamo().region()));
        if (props.dynamo().endpoint() != null && !props.dynamo().endpoint().isBlank()) {
            b.endpointOverride(URI.create(props.dynamo().endpoint()));
        }
        return b.build();
    }

    @Bean
    DynamoDbEnhancedClient enhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    @ConditionalOnProperty(name = "management.cloudwatch.metrics.export.enabled", havingValue = "true")
    CloudWatchAsyncClient cloudWatchAsyncClient(AppProperties props) {
        return CloudWatchAsyncClient.builder().region(Region.of(props.dynamo().region())).build();
    }

    /** Spring Boot 3 ships no CloudWatch export, so the Micrometer registry is wired by hand (auth.* meters only). */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "management.cloudwatch.metrics.export.enabled", havingValue = "true")
    CloudWatchMeterRegistry cloudWatchMeterRegistry(CloudWatchAsyncClient client) {
        CloudWatchConfig config = new CloudWatchConfig() {
            @Override public String get(String key) { return null; }
            @Override public String namespace() { return "card-auth-engine"; }
            @Override public Duration step() { return Duration.ofSeconds(30); }
        };
        CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
        registry.config().meterFilter(MeterFilter.denyUnless(id -> id.getName().startsWith("auth.")));
        registry.config().commonTags("application", "auth-service");
        return registry;
    }

    // Created at start-up through the Kafka AdminClient (works against MSK Serverless with IAM too).
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
