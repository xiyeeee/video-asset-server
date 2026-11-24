package com.xiye.videoasset.ai.live2d;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.live2d")
record Live2dSettings(
        URI pythonUrl, Path inputRoot, Path outputRoot,
        Duration connectTimeout, Duration readTimeout, Duration taskTimeout
) {
}
