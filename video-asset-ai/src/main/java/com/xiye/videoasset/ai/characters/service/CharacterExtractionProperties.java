package com.xiye.videoasset.ai.characters.service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.characters")
public record CharacterExtractionProperties(
        URI pythonUrl, Duration connectTimeout, Duration readTimeout, Path inputRoot,
        Path outputRoot, double frameIntervalSeconds, Duration pollInterval, Duration taskTimeout
) {
    public CharacterExtractionProperties {
        if (pythonUrl == null || pythonUrl.getHost() == null || pythonUrl.getUserInfo() != null
                || pythonUrl.getQuery() != null || pythonUrl.getFragment() != null
                || !("http".equals(pythonUrl.getScheme()) || "https".equals(pythonUrl.getScheme()))) {
            throw new IllegalArgumentException("人物提取服务地址必须为 HTTP 或 HTTPS");
        }
        if (inputRoot == null || outputRoot == null || !Double.isFinite(frameIntervalSeconds)
                || frameIntervalSeconds <= 0 || pollInterval.toSeconds() < 1 || taskTimeout.toSeconds() < 1
                || connectTimeout.toMillis() < 1 || readTimeout.toMillis() < 1
                || connectTimeout.plus(readTimeout).compareTo(Duration.ofSeconds(110)) >= 0) {
            throw new IllegalArgumentException("检查人物提取的目录、采样间隔和超时配置");
        }
    }
}
