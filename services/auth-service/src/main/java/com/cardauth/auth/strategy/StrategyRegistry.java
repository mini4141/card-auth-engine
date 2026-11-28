package com.cardauth.auth.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class StrategyRegistry {
    private final Map<String, AuthorizationStrategy> byName;
    private final String defaultName;

    @org.springframework.beans.factory.annotation.Autowired
    public StrategyRegistry(List<AuthorizationStrategy> strategies, com.cardauth.auth.config.AppProperties props) {
        this(strategies, props.defaultStrategy());
    }

    public StrategyRegistry(List<AuthorizationStrategy> strategies, String defaultName) {
        this.byName = strategies.stream().collect(Collectors.toMap(AuthorizationStrategy::name, Function.identity()));
        this.defaultName = defaultName;
    }

    /** Per-request header wins, otherwise the configured default. */
    public AuthorizationStrategy select(String requested) {
        String name = (requested == null || requested.isBlank()) ? defaultName : requested;
        AuthorizationStrategy s = byName.get(name);
        if (s == null) throw new IllegalArgumentException("unknown strategy: " + name + " (known: " + byName.keySet() + ")");
        return s;
    }
}
