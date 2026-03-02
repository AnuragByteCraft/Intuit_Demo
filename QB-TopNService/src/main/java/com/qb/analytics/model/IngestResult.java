package com.qb.analytics.model;

public class IngestResult {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_ALREADY_PROCESSED = "ALREADY_PROCESSED";

    private final String requestId;
    private final String status;

    public IngestResult(String requestId, String status) {
        this.requestId = requestId;
        this.status = status;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getStatus() {
        return status;
    }

    public static IngestResult accepted(String requestId) {
        return new IngestResult(requestId, STATUS_ACCEPTED);
    }

    public static IngestResult alreadyProcessed(String requestId) {
        return new IngestResult(requestId, STATUS_ALREADY_PROCESSED);
    }
}
