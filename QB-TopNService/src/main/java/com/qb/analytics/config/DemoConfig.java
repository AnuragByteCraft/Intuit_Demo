package com.qb.analytics.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class DemoConfig {

    @Value("${demo.asOfDate:}")
    private String asOfDateStr;

    public LocalDate today() {
        if (asOfDateStr != null && !asOfDateStr.isBlank()) {
            try {
                return LocalDate.parse(asOfDateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception ignored) {
            }
        }
        return LocalDate.now();
    }
}
