package com.xiye.videoasset.gateway;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
class RequestLogFilter implements GlobalFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedAtNanos = System.nanoTime();
        Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String traceId = exchange.getRequest().getId();

        exchange.getResponse().beforeCommit(() -> {
            LOG.info("网关请求结束 traceId={} method={} route={} status={} durationMs={}",
                    traceId,
                    exchange.getRequest().getMethod(),
                    route.getId(),
                    exchange.getResponse().getStatusCode(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
