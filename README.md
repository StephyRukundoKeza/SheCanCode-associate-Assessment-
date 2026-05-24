# Idempotency Gateway Challenge

This is my implementation of a payment middleware gateway built with Spring Boot. 
The goal of this project is pretty straightforward: make sure that if a client retries a payment request due to a network glitch or timeout, they don't end up double-charging the user.

## How it works (The Logic Flow)

```mermaid
sequenceDiagram
    autonumber
    Client->>Gateway: POST /process-payment (Idempotency-Key)
    Gateway->>Storage: Check if Key Exists
    alt Key Doesn't Exist
        Gateway->>Storage: Store Key (Status: IN_FLIGHT)
        Gateway->>Core Processor: Process Payment (2s delay)
        Gateway->>Storage: Update Key (Status: COMPLETED + Cached Response)
        Gateway-->>Client: 200 OK (Charged Successfully)
    alt Key Exists & Status is COMPLETED
        Gateway-->>Client: Return Cached Response (X-Cache-Hit: true)
    alt Key Exists But Request Body Swapped
        Gateway-->>Client: 409 Conflict (Fraud Error)
    end
