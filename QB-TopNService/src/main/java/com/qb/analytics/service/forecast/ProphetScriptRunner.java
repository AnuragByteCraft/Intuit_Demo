package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.ForecastPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    public List<ForecastPoint> predict(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) return List.of();

        File projectRoot = new File(System.getProperty("user.dir"));
        File scriptFile = new File(projectRoot, scriptPath);
        File pythonExe = new File(projectRoot, pythonPath);
        if (!pythonExe.exists()) {
            pythonExe = new File(pythonPath);
        }
        if (!scriptFile.exists()) {
            log.warn("ProphetScriptRunner script not found at {}", scriptFile.getAbsolutePath());
            return List.of();
        }

        ProcessBuilder pb = new ProcessBuilder(pythonExe.getAbsolutePath(), scriptFile.getAbsolutePath());
        pb.directory(projectRoot);
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
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            }
            String rawOutput = stdout.toString();
            if (log.isDebugEnabled()) {
                log.debug("ProphetScriptRunner raw output (first 500 chars): {}", rawOutput.length() > 500 ? rawOutput.substring(0, 500) + "..." : rawOutput);
            }
            return parsePoints(rawOutput);
        } catch (Exception e) {
            log.warn("ProphetScriptRunner failed: {}", e.getMessage());
            return List.of();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private List<ForecastPoint> parsePoints(String output) {
        if (output == null || output.isBlank()) return List.of();
        String json = extractJsonObject(output);
        if (json == null) {
            log.warn("ProphetScriptRunner could not find JSON in output (first 300 chars): {}", 
                    output.length() > 300 ? output.substring(0, 300) + "..." : output);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode pointsNode = root.get("points");
            if (pointsNode == null || !pointsNode.isArray()) {
                log.warn("ProphetScriptRunner output has no 'points' array");
                return List.of();
            }
            List<ForecastPoint> points = new ArrayList<>();
            for (JsonNode p : pointsNode) {
                String date = p.has("date") ? p.get("date").asText() : null;
                double pred = p.has("predictedSales") ? p.get("predictedSales").asDouble() : 0;
                double low = p.has("confidenceLow") ? p.get("confidenceLow").asDouble() : pred;
                double high = p.has("confidenceHigh") ? p.get("confidenceHigh").asDouble() : pred;
                if (date != null) points.add(new ForecastPoint(date, pred, low, high));
            }
            log.info("ProphetScriptRunner parsed {} forecast points from output", points.size());
            return points;
        } catch (Exception e) {
            log.warn("ProphetScriptRunner parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonObject(String output) {
        int idx = output.indexOf("{\"points\":");
        if (idx < 0) idx = output.indexOf("{");
        if (idx < 0) return null;
        int depth = 0;
        for (int i = idx; i < output.length(); i++) {
            char c = output.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return output.substring(idx, i + 1);
            }
        }
        return null;
    }
}
