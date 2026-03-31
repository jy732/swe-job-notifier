package com.github.jingyangyu.swejobnotifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.github.jingyangyu.swejobnotifier.config.WorkdayProperties;

/**
 * Entry point for the SWE Job Notifier application. Monitors tech company career sites for
 * mid-level Software Engineer II job postings, classifies them using Gemini Flash LLM, and sends
 * email notifications.
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
@EnableConfigurationProperties(WorkdayProperties.class)
public class SweJobNotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(SweJobNotifierApplication.class, args);
    }
}
