package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.ForecastPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs local Prophet Python script: stdin = request JSON, stdout = forecast points JSON. */
@Component
public class ProphetScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(ProphetScriptRunner.class);
    private static final int TIMEOUT_SECONDS = 60;

    private final String pythonPath;
    private final String scriptPath;
    private final ObjectMapper objectMapper;

    public ProphetScriptRunner(@Value("${demo.forecast.prophetPythonPath:python3}") String pythonPath,
                               @Value("${demo.forecast.prophetScriptPath:scripts/prophet_predict.py}") String scriptPath,
                               ObjectMapper objectMapper) {
        this.pythonPath = pythonPath;
        this.scriptPath = scriptPath;
        this.objectMapper = objectMapper;
    }

    /** Run script with request JSON (history + horizonDays); returns points or empty list on failure. */
    public List<ForecastPoint> predict(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) return List.of();

        ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath);
        pb.redirectErrorStream(true);
        Process process = null;
        try {
            process = pb.start();
            try (var stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                stdin.write(requestJson);
                stdin.flush();
            }
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ProphetScriptRunner script timed out after {}s", TIMEOUT_SECONDS);
                return List.of();
            }
            if (process.exitValue() != 0) {
                log.warn("ProphetScriptRunner script exited with code {}", process.exitValue());
                return List.of();
            }
            StringBuilder stdout = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) stdout.append(line);
            }
            return parsePoints(stdout.toString());
        } catch (Exception e) {
            log.warn("ProphetScriptRunner failed: {}", e.getMessage());
            return List.of();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private List<ForecastPoint> parsePoints(String output) {
        if (output == null || output.isBlank()) return List.of();
        String json = output.trim();
        if (json.contains("\n")) {
            String[] lines = json.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (!line.isEmpty() && line.startsWith("{")) {
                    json = line;
                    break;
                }
            }
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode pointsNode = root.get("points");
            if (pointsNode == null || !pointsNode.isArray()) return List.of();
            List<ForecastPoint> points = new ArrayList<>();
            for (JsonNode p : pointsNode) {
                String date = p.has("date") ? p.get("date").asText() : null;
                double pred = p.has("predictedSales") ? p.get("predictedSales").asDouble() : 0;
                double low = p.has("confidenceLow") ? p.get("confidenceLow").asDouble() : pred;
                double high = p.has("confidenceHigh") ? p.get("confidenceHigh").asDouble() : pred;
                if (date != null) points.add(new ForecastPoint(date, pred, low, high));
            }
            return points;
        } catch (Exception e) {
            log.warn("ProphetScriptRunner parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
