package com.xiye.videoasset.media;

import com.xiye.videoasset.common.security.ResourceServerConfiguration;
import com.xiye.videoasset.common.web.ApiExceptionHandler;
import com.xiye.videoasset.common.web.DevelopmentProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ResourceServerConfiguration.class, ApiExceptionHandler.class, DevelopmentProbe.class})
public class MediaApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
