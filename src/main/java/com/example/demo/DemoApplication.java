package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

class IdempotencyRecord {
    enum Status { IN_FLIGHT, COMPLETED }
    private final String bodyString;
    private final long createdAt; // Developer's Choice: Track entry lifetime
    private Status status = Status.IN_FLIGHT;
    private String responseBody;

    public IdempotencyRecord(String bodyString) { 
        this.bodyString = bodyString; 
        this.createdAt = System.currentTimeMillis();
    }
    public String getBodyString() { return bodyString; }
    public long getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
}

@RestController
@RequestMapping("/process-payment")
class PaymentController {
    private final ConcurrentHashMap<String, IdempotencyRecord> storage = new ConcurrentHashMap<>();
    
    // Developer's Choice: Background cleaner scheduling service
    private final ScheduledExecutorService cleanerService = Executors.newSingleThreadScheduledExecutor();
    private static final long TTL_MS = TimeUnit.MINUTES.toMillis(5); // 5-minute validity window

    public PaymentController() {
        // Run memory eviction cycle every 60 seconds automatically
        this.cleanerService.scheduleAtFixedRate(this::evictExpiredKeys, 1, 1, TimeUnit.MINUTES);
    }

    @PostMapping
    public ResponseEntity<?> processPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody String rawBody) {

        if (key == null || key.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing Idempotency-Key header"));
        }

        IdempotencyRecord record = storage.putIfAbsent(key, new IdempotencyRecord(rawBody));

        if (record == null) {
            IdempotencyRecord freshRecord = storage.get(key);
            return executePayment(freshRecord, rawBody);
        }

        synchronized (record) {
            if (!record.getBodyString().equals(rawBody)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Idempotency key already used for a different request body."));
            }

            while (record.getStatus() == IdempotencyRecord.Status.IN_FLIGHT) {
                try {
                    record.wait(); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ResponseEntity.internalServerError().build();
                }
            }

            return ResponseEntity.ok()
                    .header("X-Cache-Hit", "true")
                    .body(record.getResponseBody());
        }
    }

    private ResponseEntity<?> executePayment(IdempotencyRecord record, String rawBody) {
        try {
            Thread.sleep(2000);
            String successResponse = "{\"status\": \"Charged successfully\", \"details\": " + rawBody + "}";

            synchronized (record) {
                record.setResponseBody(successResponse);
                record.setStatus(IdempotencyRecord.Status.COMPLETED);
                record.notifyAll();
            }

            return ResponseEntity.ok().body(successResponse);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // Developer's Choice: Eviction function loop to protect server memory bounds
    private void evictExpiredKeys() {
        long now = System.currentTimeMillis();
        storage.entrySet().removeIf(entry -> {
            boolean isExpired = (now - entry.getValue().getCreatedAt()) > TTL_MS;
            // Only clean up if it's completely finished processing to avoid wiping in-flight active traffic
            return isExpired && entry.getValue().getStatus() == IdempotencyRecord.Status.COMPLETED;
        });
    }
}