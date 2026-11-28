package com.cardauth.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String defaultStrategy, String instanceId, Dynamo dynamo, Reconcile reconcile) {
    public record Dynamo(String endpoint, String region, String table, boolean createTable) {}
    public record Reconcile(long intervalMs) {}
}
