package com.FGInteractive.BatalhaNaval.shared.dto;

public class HealthResponse {
    
    private String status;
    private String message;
    private long timestamp;

    public HealthResponse() {
        this.status = "UP";
        this.message = "Application is running";
        this.timestamp = System.currentTimeMillis();
    }

    public HealthResponse(String status, String message) {
        this.status = status;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
