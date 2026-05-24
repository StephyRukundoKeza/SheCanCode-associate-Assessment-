package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

class IdempotencyRecord {
    enum Status { IN_FLIGHT, COMPLETED }
    private final String bodyString;
    private Status status = Status.IN_FLIGHT;
    private String responseBody;

    public IdempotencyRecord(String bodyString) { this.bodyString = bodyString; }
    public String getBodyString() { return bodyString; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
}

@RestController
@RequestMapping("/process-payment")
class PaymentController {
    private final ConcurrentHashMap<String, IdempotencyRecord> storage = new ConcurrentHashMap<>();

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
}
