package com.xiye.videoasset.media;

import com.xiye.videoasset.api.extraction.ExtractionJobWriter;
import com.xiye.videoasset.api.extraction.ExtractionJobs;
import com.xiye.videoasset.api.extraction.ExtractionPlan;
import com.xiye.videoasset.common.security.ResourceServerConfiguration;
import com.xiye.videoasset.common.web.ApiExceptionHandler;
import com.xiye.videoasset.common.web.DevelopmentProbe;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@MapperScan(basePackages = {"com.xiye.videoasset.media", "com.xiye.videoasset.api.extraction"},
        annotationClass = Mapper.class)
@Import({ResourceServerConfiguration.class, ApiExceptionHandler.class, DevelopmentProbe.class})
public class MediaApplication {
    @Bean
    ExtractionPlan extractionPlan(@Value("${EXTRACTION_SUPPORTED_TYPES:SHOT,CHARACTER,AUDIO}") String types) {
        return new ExtractionPlan(types);
    }

    @Bean
    ExtractionJobs extractionJobs(ExtractionJobWriter writer) {
        return new ExtractionJobs(writer);
    }

    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
