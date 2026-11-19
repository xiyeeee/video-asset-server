package com.xiye.videoasset.common.web;

import com.xiye.videoasset.common.ApiPaths;
import com.xiye.videoasset.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev & !prod")
public class DevelopmentProbe {
    private final String serviceName;
    private final String marker;

    public DevelopmentProbe(@Value("${spring.application.name}") String serviceName,
                            @Value("${app.dev.probe-marker:local}") String marker) {
        this.serviceName = serviceName;
        this.marker = marker;
    }

    @GetMapping(ApiPaths.DEV_PROBE)
    Result<Probe> ping() {
        return Result.ok(new Probe(serviceName, marker));
    }

    record Probe(String service, String marker) {
    }
}
