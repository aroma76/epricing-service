# Beginner-Friendly Complete Guide to ePricing Observability Stack

> Who this is for: Someone with basic programming knowledge who wants to understand
> this entire project from scratch, without needing to read any other document.

## Table of Contents

1. What is this Project?
2. Why Do We Need Observability?
3. The 7 Tools in This Project
4. How a Single Request Travels Through the System
5. The ePricing Service - The Main App
6. Prometheus - The Numbers Collector
7. Grafana - The TV Screen Dashboard
8. Loki - The Log Vault
9. Promtail - The Log Delivery Truck
10. OpenTelemetry - The GPS Tracker
11. Traffic Generator - The Fake Customer Robot
12. Docker - The Kitchen That Runs Everything
13. Key Metrics Explained (Manager Assignment)
14. How to Start and Use This System
15. Common Mistakes and How to Fix Them
16. Quick Reference Summary

---

## 1. What is this Project?

### Simple Explanation

Imagine a bank where customers apply for loans. A bank officer manually looks at your
credit score, income, and loan amount, then tells you the interest rate and monthly
payment (EMI).

This project AUTOMATES that job using software. Instead of a bank officer, there
is a Java program that does the math in milliseconds.

But just having the program is not enough. We also need:
- A way to watch if the program is running fine (like a health monitor)
- A way to read what happened when something goes wrong (like a diary of events)
- A dashboard to see everything at a glance (like a control room TV screen)

That is what this entire project builds: the program AND the monitoring system around it.

### Technical Explanation

This is a Spring Boot microservice (a small, focused Java web application) called
epricing-service. It exposes REST API endpoints that calculate:

1. Risk-adjusted interest rates (based on credit score, loan amount, product type)
2. Exact monthly EMIs using the standard banking compound-interest formula
3. Audit records for RBI regulatory compliance

Wrapped around it is a full Observability Stack - a set of industry-standard tools
(Prometheus, Grafana, Loki, Promtail, OpenTelemetry) that monitor the app health,
performance, and logs in real time.

---

## 2. Why Do We Need Observability?

### Simple Explanation

Imagine you manage a restaurant kitchen. Without monitoring:

  "Chef, is everything ok?" -> "Yes!" -> But customers are complaining about 30-minute waits!

With monitoring you watch a TV screen that shows:
- How many orders are being cooked right now
- How long each order takes
- If any dishes were sent back as errors
- What exactly went wrong at 3:47 PM

Observability means giving your software this same TV screen so you can see what
is happening WITHOUT guessing.

### Technical Explanation

Observability has three pillars:

| Pillar | Question it answers | Tool we use |
|---|---|---|
| Metrics | How many requests? How fast? CPU? RAM? | Prometheus + Grafana |
| Logs | What exact text was written when it failed? | Loki + Promtail |
| Traces | Which exact path did this request take? | OpenTelemetry |

Without all three, you are flying blind. With all three, you can pinpoint any problem in seconds.

---

## 3. The 7 Tools in This Project

Here is a one-line explanation of every tool:

`
+---------------------------+------------------------------------------+
| 1. epricing-service       | The main Java app. Calculates loan rates. |
| 2. Prometheus             | Collects and stores numbers (metrics).   |
| 3. Grafana                | Draws graphs from Prometheus data.       |
| 4. Loki                   | Stores log text (errors, events).        |
| 5. Promtail               | Reads log files, ships them to Loki.     |
| 6. OpenTelemetry (OTel)   | Tracks the path of each request.        |
| 7. traffic-generator      | Robot that sends fake requests for demo. |
+---------------------------+------------------------------------------+
`

### Real-World Analogy Map

| Tool | Analogy |
|---|---|
| epricing-service | The restaurant kitchen (processes loans) |
| Prometheus | The cash register (counts every sale, every minute) |
| Grafana | The manager's TV screen (shows graphs from cash register data) |
| Loki | The security camera recording vault (stores every event) |
| Promtail | The security camera (reads events and sends to the vault) |
| OpenTelemetry | GPS tracker on each delivery (follows every request step-by-step) |
| traffic-generator | Test customer robot (keeps ordering so graphs are never empty) |

---

## 4. How a Single Request Travels Through the System

Let us follow one loan request step by step like a story.

RAVI APPLIES FOR A HOME LOAN of Rs.50 lakhs.

Step 1: Ravi sends a request.
The traffic-generator (or Ravi's browser) sends this to our app:

  POST http://localhost:8080/api/v1/pricing
  {
    "customer_id": "CUST001234",
    "product_type": "HOME_LOAN",
    "loan_amount": 5000000,
    "loan_tenure_months": 240,
    "credit_score": 780,
    "annual_income": 2400000
  }

Step 2: The app receives it and starts tracking.
The moment the request arrives, the app does three things automatically:
- Micrometer adds 1 to the request counter metric.
- OpenTelemetry attaches a unique traceId (like a tracking number) to the request.
- A timer starts measuring how long this request takes.

Step 3: The app validates eligibility.
- Is the credit score above 550? Ravi has 780, so PASS.
- Does the income support this loan amount? PASS.
If these fail, it logs an ERROR and rejects immediately. Ravi's request passes.

Step 4: Calculate the interest rate.
- Base rate for HOME_LOAN = 8.50%
- Credit score 780 (high score = low risk) = discount of -0.50%
- Final rate = 8.00% per annum

Step 5: Calculate the monthly EMI.
Using the standard banking formula:
  EMI = P x r x (1+r)^n / ((1+r)^n - 1)
      = Rs. 41,822 per month

Step 6: Save result and write a log.
The app saves the pricing record to the H2 database and writes a log line:
  { "level":"INFO", "message":"Pricing calculated", "customerId":"CUST001234",
    "interestRate":8.00, "emi":41822.45, "traceId":"4bf92f3577b34da6..." }

Step 7: The observability system takes over.
- Every 10 seconds: Prometheus visits /actuator/prometheus, downloads metric numbers.
- Immediately: The log line is written to a file on disk, Promtail reads it, ships to Loki.
- Real-time: OTel Collector receives the trace span and forwards it.
- On Grafana: Dashboard auto-refreshes showing new counts, latency, and logs.

### Full Data Flow Diagram

  [traffic-generator] --POST /api/v1/pricing--> [epricing-service (Spring Boot)]
                                                    |          |           |
                                          logfile   | /actuator| OTLP      |
                                          on disk   |/prometheus trace     |
                                                    v          v           v
                                              [Promtail] [Prometheus] [OTel Collector]
                                                    |          |
                                                    v          v
                                               [Loki]     [Grafana :3000]
                                                    |          ^
                                                    +----------+

---

## 5. The ePricing Service - The Main App

### Simple Explanation

This is the main Java program that does all the banking work.

Think of it as a smart calculator that:
- Takes in: Who you are, how much you want to borrow, how long you will repay.
- Gives back: Your interest rate and monthly EMI amount.

### How the Code is Organized

Each folder in the Java source code has one job, like departments in a company:

`
src/main/java/com/bank/epricing/
|
+-- controller/   <- Reception Desk: Receives HTTP requests from customers
|   +-- PricingController.java       (handles POST /api/v1/pricing)
|   +-- HealthController.java        (handles GET /api/v1/health)
|   +-- MetricsDemoController.java   (demo/simulation endpoints)
|
+-- service/      <- Manager's Office: Makes the real business decisions
|   +-- PricingService.java          (main orchestrator)
|   +-- PricingAuditService.java     (saves audit records)
|
+-- repository/   <- Filing Cabinet: Talks to the H2 database
|
+-- entity/       <- Paper Form: Defines what data looks like in the database
|
+-- dto/          <- Message Templates: What data enters and exits the API
|
+-- metrics/      <- Scorekeeper: Records counts, timings for Prometheus
|
+-- logging/      <- Secretary: Writes structured JSON log entries
|
+-- util/         <- Calculator: Contains the actual EMI math formula
|
+-- exception/    <- Complaint Dept: Handles errors gracefully
`

### The 3 Most Important Endpoints

1. Calculate a Loan Price
   POST http://localhost:8080/api/v1/pricing
   Send credit score, loan amount, tenure -> Get back interest rate and EMI.

2. Simulate Slow Response (for Grafana testing)
   GET http://localhost:8080/api/v1/metrics-demo/simulate-slow
   Deliberately waits 1-3 seconds. Makes P95 Latency spike upward on Grafana.
   Use this to show how the latency alert system works during your demo.

3. Simulate an Error (for Loki log testing)
   GET http://localhost:8080/api/v1/metrics-demo/simulate-error
   Deliberately throws an exception and writes an ERROR log line.
   Go to Grafana -> Explore -> Loki to see it appear immediately.

### How the Interest Rate is Calculated

The app uses risk-based pricing. The riskier the customer, the higher the rate.

Base Rates by Product:
| Product Type     | Base Rate |
|---|---|
| HOME_LOAN        | 8.50%     |
| EDUCATION_LOAN   | 9.00%     |
| AUTO_LOAN        | 9.35%     |
| BUSINESS_LOAN    | 11.00%    |
| PERSONAL_LOAN    | 13.25%    |

Credit Score Adjustments:
| Credit Score     | Risk Level      | Rate Change        |
|---|---|---|
| 780 and above    | LOW RISK        | -0.50% (discount)  |
| 700 to 779       | MEDIUM RISK     | No change          |
| 650 to 699       | HIGH RISK       | +0.75% (surcharge) |
| Below 650        | VERY HIGH RISK  | +1.50% (surcharge) |

Example: HOME_LOAN + Credit Score 780 = 8.50% - 0.50% = FINAL RATE: 8.00% p.a.

---

> SECTION 5 KEY TAKEAWAY: The epricing-service receives loan requests, does risk-based
> calculations, saves records, and emits metrics + logs + traces simultaneously.

---

## 6. Prometheus - The Numbers Collector

### Simple Explanation

Imagine you run a pizza shop. Every few minutes you count:
- How many pizzas were sold?
- How long did each pizza take to make?
- How much flour is left in stock?

Prometheus does exactly this for our app, but EVERY 10 SECONDS, automatically.

It visits our app and says: "Give me all your current numbers." Our app gives back
thousands of numbers. Prometheus saves them with a timestamp so you can see how
numbers changed over time.

### What is a "Scrape"?

A scrape is when Prometheus makes an HTTP GET request to our app's special URL:

  GET http://epricing-service:8081/actuator/prometheus

The app responds with raw text like this:

  # HELP process_cpu_usage Recent CPU usage for the JVM process
  # TYPE process_cpu_usage gauge
  process_cpu_usage 0.142

  jvm_memory_used_bytes{area="heap"} 89128960.0

  pricing_requests_total{product_type="HOME_LOAN"} 42.0

Each line = metric name + labels in {curly braces} + the number value.

Prometheus reads this and stores it in its TIME-SERIES DATABASE - a special database
optimized for storing numbers indexed by time.

> Time-Series Database: Like a spreadsheet where every row has a timestamp and a number.
> You can ask: "What was CPU usage at 3:47 PM yesterday?"

### The PULL Model (Why Prometheus pulls instead of app pushing)

Prometheus PULLS data FROM the app. The app does NOT send data to Prometheus.

  Prometheus -> HTTP GET /actuator/prometheus -> App
  App        -> returns raw metric text        -> Prometheus
  Prometheus saves it, waits 10 seconds, then asks again.

Why PULL is better:
- If the app crashes, Prometheus immediately knows (the scrape fails = target is down)
- Prometheus controls timing - consistent, predictable data intervals

### The 4 Types of Metrics

TYPE 1: Counter - Only goes up, never down.
> Analogy: An odometer on a car. Always goes up as you drive. Never resets.
  Example: pricing_requests_total - total loan requests ever processed.
  pricing_requests_total{product_type="HOME_LOAN"} 1250

TYPE 2: Gauge - Can go up or down. Measures current state right now.
> Analogy: A speedometer. Shows your current speed, which goes up and down.
  Example: process_cpu_usage - current CPU usage right now.
  process_cpu_usage 0.142   <- means 14.2% CPU is being used RIGHT NOW

TYPE 3: Histogram - Measures how values are distributed across ranges.
> Analogy: Sorting apples into small, medium, large bins.
  Example: http_server_requests_seconds_bucket - how many requests took each duration:
  {le="0.1"}  980  <- 980 requests took less than 0.1 seconds
  {le="0.5"} 1200  <- 1200 requests took less than 0.5 seconds
  {le="1.0"} 1245  <- 1245 requests took less than 1.0 seconds
  This lets Grafana calculate the P95 latency.

TYPE 4: Summary - Similar to Histogram but pre-calculated.

### PromQL - How to Ask Prometheus for Data

PromQL (Prometheus Query Language) is how Grafana asks Prometheus for numbers.
Every graph panel in Grafana uses a PromQL query behind the scenes.

| What you want to see | PromQL Query |
|---|---|
| Current CPU usage | process_cpu_usage |
| RAM used in MB | jvm_memory_used_bytes / 1024 / 1024 |
| Requests per second | rate(pricing_requests_total[1m]) |
| P95 response time | histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) |

IMPORTANT: Always use rate() on Counters in Grafana, not the raw counter value.
rate() converts "total count ever" into "how many per second in the last N minutes".

---

> SECTION 6 KEY TAKEAWAY: Prometheus PULLS numbers from your app every 10 seconds and
> stores them in a time-series database. PromQL queries those numbers. Grafana draws
> graphs from PromQL results.

---

## 7. Grafana - The TV Screen Dashboard

### Simple Explanation

Grafana is the visual control room. Imagine a hospital: doctors do not read raw data
files. They look at monitors showing heart rate, blood pressure, and temperature as
live graphs.

Grafana is that monitor for our banking application.

### Key Facts About Grafana

1. Grafana stores NO data of its own. It only queries other tools and draws graphs.
2. It queries Prometheus for metrics and Loki for logs.
3. Every graph panel uses a PromQL or LogQL query behind the scenes.

### Dashboard Layout on Your Screen (http://localhost:3000)

  +------------------------------------------------------------------+
  |           ePricing Complete Observability Dashboard              |
  +-------------+-------------+------------+------------------------+
  |  CPU Usage  |   RAM Used  | Disk Space |   Service Status       |
  |  [Gauge]    |  [Gauge]    |  [Gauge]   |   [Green = UP]         |
  +-------------+-------------+------------+------------------------+
  |            Request Rate (requests per second over time)         |
  |            [Line Chart - shows peaks and valleys of traffic]    |
  +---------------------------------+--------------------------------+
  |   P95 Response Time (latency)   |   Error Rate (%)              |
  |   [Should stay below 2000ms]    |   [Should stay below 5%]      |
  +---------------------------------+--------------------------------+
  |              JVM Memory: Heap vs Non-Heap over time             |
  +------------------------------------------------------------------+
  |              Loki Logs (Live Error Feed)                        |
  |              [Shows last 20 ERROR and WARN log lines]           |
  +------------------------------------------------------------------+

### Access Details

- URL: http://localhost:3000
- Username: admin
- Password: Bank@grafana123

---

> SECTION 7 KEY TAKEAWAY: Grafana is a visual layer only - stores nothing. It queries
> Prometheus for metrics and Loki for logs, then draws beautiful graphs.

---

## 8. Loki - The Log Vault

### Simple Explanation

Every time something happens in the app, the app writes a text note about it.
These notes are called LOGS. Examples:

- "Customer CUST001234 requested HOME_LOAN - SUCCESS" -> INFO log
- "Credit score 450 is too low - REJECTED" -> WARN log
- "Database connection failed!" -> ERROR log

Loki is the vault that stores all these text notes, searchable by label.

### What is a Log Line?

Our app writes logs in JSON format. A typical log line:

  {
    "timestamp": "2026-08-03T10:45:23.123Z",
    "level": "INFO",
    "message": "Pricing calculated successfully",
    "customerId": "CUST001234",
    "productType": "HOME_LOAN",
    "interestRate": 8.00,
    "emi": 41822.45,
    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
  }

Why JSON? Plain text logs are hard to search. JSON lets Loki extract fields like
traceId, customerId, and level as searchable labels.

### How Loki Differs from Traditional Log Systems (like Elasticsearch)

| Feature | Elasticsearch (traditional) | Loki |
|---|---|---|
| What it indexes | Every word in every log | Only LABELS (level, application) |
| Storage cost | Very high | Very low |
| Best for | Full-text search | Label-based filtering |
| Setup complexity | Very complex | Simple single-node |

### LogQL - Loki's Query Language

| What you want | LogQL Query |
|---|---|
| All logs from our app | {application="epricing-service"} |
| Only ERROR logs | {application="epricing-service"} | json | level="ERROR" |
| Logs for one customer | {application="epricing-service"} | json | customerId="CUST001234" |
| Find a trace | {application="epricing-service"} | json | traceId="4bf92f..." |

Loki keeps logs for 168 hours (7 days) before deleting old ones (configured in loki-config.yml).

---

> SECTION 8 KEY TAKEAWAY: Loki stores log text indexed by labels only, making it
> extremely storage-efficient. Search using LogQL. Grafana shows Loki results on the
> same dashboard as your metrics.

---

## 9. Promtail - The Log Delivery Truck

### Simple Explanation

Loki stores logs. But how do logs GET INTO Loki? That is Promtail's job.

Think of it like this:
- The app writes notes (logs) onto paper and drops them in a mail slot (log file on disk).
- Promtail is the postman who picks up those notes from the mail slot,
  stamps the sender's name on the envelope (adds labels), and delivers them to Loki.

### What Promtail Does Step by Step

  Step 1: App writes a JSON log line to /app/logs/epricing-service.log
  Step 2: Promtail is "tailing" (watching) this file constantly.
          (Like running: tail -f /app/logs/epricing-service.log)
  Step 3: Promtail sees the new line, parses the JSON, and adds labels:
          { application="epricing-service", level="INFO" }
  Step 4: Promtail sends this labeled log entry to Loki:
          POST http://loki:3100/loki/api/v1/push
  Step 5: Loki stores the entry in its compressed chunks database.

### The Shared Docker Volume Trick

Both epricing-service and promtail access the same Docker volume (epricing-logs):

  Docker Volume: epricing-logs
       |
       +-- Mounted in epricing-service at: /app/logs/
       |   (App WRITES logs here)
       |
       +-- Mounted in promtail at: /var/log/epricing/
           (Promtail READS logs from here)

This is how two separate Docker containers share files without any network connection.
They share the same folder through the Docker volume system.

---

> SECTION 9 KEY TAKEAWAY: Promtail watches log files, adds labels, and pushes new
> log entries to Loki. It uses a shared Docker volume to access the app's log files.

---

## 10. OpenTelemetry - The GPS Tracker

### Simple Explanation

Imagine ordering a pizza online. The app gives you a tracking number. Using that number
you see: "Order placed -> Cooking -> Quality check -> Out for delivery -> Delivered."
You know exactly how long each step took.

OpenTelemetry does this for every loan request. It gives each request a unique traceId
(tracking number) and records how long each step inside the code took.

If a loan request takes 3 seconds, you open the trace and see:
- Database query: 2.8 seconds  <-- HERE is the slow part!
- Calculation: 0.1 seconds
- Validation: 0.1 seconds

### The 3 Key Concepts

1. TRACE - The complete journey of one request from start to finish.
2. SPAN - One step within that journey. A trace is made of many spans.
3. TRACEID - The unique ID that links all spans of one request together.

Trace for traceId "4bf92f3577b34da..."
|
+-- Span: "GET /api/v1/pricing" (total: 150ms)     <- auto-created by Spring Boot
    |
    +-- Span: "calculatePricing" (140ms)            <- created in PricingService.java
        |
        +-- Span: "validateEligibility" (5ms)       <- validation step
        +-- Span: "computeInterestRate" (10ms)      <- rate calculation step
        +-- Span: "persistPricingRequest" (125ms)   <- database save step

This visualization is called a waterfall diagram. It immediately shows where time is spent.

### The OTel Collector - The Telemetry Router

The OTel Collector receives telemetry from the app and fans it out to multiple tools:

  App sends OTLP data --> OTel Collector --> Prometheus
                                         --> Loki
                                         --> Jaeger/Tempo (if configured)

Without Collector: App needs separate code plugins for every tool.
With Collector: App sends ONE stream. Collector handles all the routing.
Switching from Jaeger to Zipkin? Change Collector config only. No app code changes.

### The traceId in Logs (Correlated Observability)

Every log line includes the traceId. This is the BRIDGE between logs and traces.

If a customer reports an error:
1. Search Loki for their customerId -> find the log -> note the traceId
2. Search the tracing tool with that traceId -> see exactly which step failed

This is called CORRELATED OBSERVABILITY and is extremely powerful for debugging.

---

> SECTION 10 KEY TAKEAWAY: OpenTelemetry assigns a unique traceId to every request and
> records how long each step takes. The traceId appears in both traces AND logs, letting
> you jump between them to debug issues instantly.

---

## 11. Traffic Generator - The Fake Customer Robot

### Simple Explanation

Grafana only shows graphs if real data is coming in. Without any customer requests,
all graphs would be empty and flat - useless for a demo or presentation.

The traffic-generator is a simple robot that sends fake loan requests to the app
every 2 seconds. This keeps the graphs alive and the logs flowing during your demo.

### What It Is

It is a Docker container running alpine/curl - a tiny Linux container with just the
curl HTTP tool installed. It runs this script in an infinite loop:

  while true; do
    # Send a Home Loan request (generates INFO log + metric)
    curl -X POST http://epricing-service:8080/api/v1/pricing \
      -d '{"customer_id":"CUST001234","product_type":"HOME_LOAN",...}'

    # Trigger slow response (makes latency spike on Grafana)
    curl http://epricing-service:8080/api/v1/metrics-demo/simulate-slow

    # Trigger an error (makes ERROR logs appear in Loki)
    curl http://epricing-service:8080/api/v1/metrics-demo/simulate-error

    sleep 2   # Wait 2 seconds, then repeat forever
  done

### What Each Request Triggers

| Request | What it generates |
|---|---|
| POST /api/v1/pricing (HOME_LOAN) | INFO log + counter increments |
| POST /api/v1/pricing (AUTO_LOAN) | INFO log + counter increments |
| GET /metrics-demo/simulate-slow | WARN log + latency spike on Grafana |
| GET /metrics-demo/simulate-error | ERROR log + error rate increase |

### Two Ways to Run Traffic

| Method | How | When to use |
|---|---|---|
| Automatic (Docker) | Part of docker-compose.yml | Default. Always running when Docker is up. |
| Manual (PowerShell) | Run simulate_traffic.ps1 | When you want live output in your terminal |

---

> SECTION 11 KEY TAKEAWAY: traffic-generator is a curl-in-a-loop robot that only
> exists because Grafana needs real requests to show graphs. In production, real
> customers replace it entirely.

---

## 12. Docker - The Kitchen That Runs Everything

### Simple Explanation

Imagine cooking a meal using 7 appliances: oven, microwave, blender, coffee machine,
toaster, pressure cooker, and a juicer. Each needs specific settings and they must
all work together.

Docker is the kitchen that houses all appliances.
Docker Compose is the recipe card that says: "Start the oven first, then the pressure
cooker, then all others."

One command starts your entire monitoring environment in the right order, with all
the right settings.

### What Docker Does

Docker runs each tool in an ISOLATED CONTAINER - like a mini-virtual-machine containing
only the software needed for that one tool.

  +--------------------------------------------------+
  |                  Your Computer                   |
  |                                                  |
  |  +----------+  +----------+  +---------------+  |
  |  |  Spring  |  |Prometheus|  |    Grafana    |  |
  |  |  Boot    |  |  :9090   |  |    :3000      |  |
  |  |  :8080   |  |          |  |               |  |
  |  +----------+  +----------+  +---------------+  |
  |                                                  |
  |        All containers on the same private network|
  +--------------------------------------------------+

### Key Concepts in docker-compose.yml

NETWORKS - How containers find each other:
Inside Docker, containers CANNOT use "localhost" to talk to each other.
They use the container NAME as the hostname instead.

  # In prometheus.yml, the container NAME is used (not an IP address):
  targets: ['epricing-service:8081']

Docker has a built-in DNS system. When Prometheus says "connect to epricing-service",
Docker looks up the IP of the container with that name and connects automatically.

VOLUMES - How data survives restarts:
  volumes:
    prometheus-data:   <- Prometheus metrics history survives restarts
    grafana-data:      <- Grafana dashboards survive restarts
    loki-data:         <- Log history survives restarts
    epricing-logs:     <- SHARED: app writes logs, promtail reads them

Without volumes: docker compose down deletes ALL metric history and dashboards.
With volumes: Data persists even through complete container restarts.

PORTS - How you access services from your browser:
  ports:
    - "3000:3000"   <- Your laptop:3000 -> Grafana container:3000
    - "9090:9090"   <- Your laptop:9090 -> Prometheus container:9090
    - "8080:8080"   <- Your laptop:8080 -> Spring Boot container:8080

You type http://localhost:3000 -> Docker routes it to the Grafana container.

HEALTH CHECKS - Docker knows if a service is ready:
  healthcheck:
    test: wget http://localhost:8081/actuator/health
    interval: 30s    <- Check every 30 seconds
    timeout: 10s     <- If no reply in 10s = failed
    retries: 3       <- Fail 3 times in a row = container is "unhealthy"

---

> SECTION 12 KEY TAKEAWAY: Docker isolates each tool in its own container. Docker
> Compose starts all 7 with one command. Containers find each other by service name
> (not IP). Named volumes persist data across restarts.

---

## 13. Key Metrics Explained (Manager's Assignment)

This section covers exactly what your manager asked you to demonstrate.

### CPU Utilisation

What CPU is:
The CPU (Central Processing Unit) is the brain of a computer. It runs the Java code.
When you calculate an EMI, the CPU does the math.

The Metric: process_cpu_usage

  process_cpu_usage = 0.142  -> means 14.2% of the CPU is used by Java right now

Simple Rule:
- 0% to 50%  = Normal. Healthy.
- 50% to 80% = Getting busy. Watch it.
- Above 80%  = CRITICAL. Requests will slow down or fail.

Where to see it: Grafana -> Dashboard -> Top-left CPU gauge panel.

---

### RAM / Memory Utilisation - Heap vs Non-Heap

What RAM is:
RAM (Random Access Memory) is the temporary workspace Java uses while running.
When processing a loan request, Java holds the customer's data in RAM.

Java Memory has TWO areas:

  +------------------------------------------------------------------+
  |                         JVM Memory                              |
  |                                                                  |
  |  +-----------------------------+  +---------------------------+  |
  |  |          HEAP               |  |       NON-HEAP            |  |
  |  |                             |  |                           |  |
  |  |  Stores: customer objects,  |  |  Stores: the Java code    |  |
  |  |  pricing results, database  |  |  itself (class definitions|  |
  |  |  records held in memory     |  |  and compiled bytecode)   |  |
  |  |                             |  |                           |  |
  |  |  Managed by GC (Garbage     |  |  Managed by the JVM       |  |
  |  |  Collector - auto-cleanup)  |  |  Does not grow much       |  |
  |  +-----------------------------+  +---------------------------+  |
  +------------------------------------------------------------------+

Heap Analogy: Your office desk - where you put papers (objects) to work on right now.
When done, the cleaner (Garbage Collector) removes old papers.

Non-Heap Analogy: The filing cabinet where the company's procedures (code) are stored
permanently. It barely changes once the app starts.

The Metrics:
- jvm_memory_used_bytes{area="heap"} - How much heap RAM is used right now
- jvm_memory_max_bytes{area="heap"} - The maximum allowed heap RAM

Simple Rule:
- Used < 60% of max = Healthy
- Used > 80% of max = Warning. GC is working very hard.
- Used = 100% of max = CRITICAL. App crashes with OutOfMemoryError.

---

### Garbage Collector (GC) - The Memory Cleaner

What GC is:
Java automatically deletes objects from Heap that are no longer needed.
This process is called Garbage Collection (GC).

GC Analogy: A cleaning crew in your office. They periodically come in and remove
all papers (objects) you are no longer using.

The Problem: Stop-The-World (STW) Pauses
During some GC types, Java COMPLETELY PAUSES the application to clean memory.
During this pause, NO customer requests are processed. This causes latency spikes.

STW Analogy: The cleaning crew says "Everyone out! We need to clean for 200 milliseconds."
Every customer at the bank counter has to wait.

The Metrics:
- jvm_gc_pause_seconds_count - How many GC pauses happened
- jvm_gc_pause_seconds_sum   - Total time spent paused

Simple Rule:
- Short pauses (< 100ms) = Normal
- Frequent long pauses (> 500ms) = Memory pressure. Need more heap or fix memory leak.

---

### Disk Utilisation

What Disk is:
The hard drive where data is permanently stored (survives after computer turns off).

The Metric: disk_free_bytes (or diskspace_free_bytes)

Simple Rule:
- Free disk > 20% = Healthy
- Free disk < 10% = Warning. Loki log chunks may fail to write.
- Free disk = 0   = CRITICAL. App cannot save records. Crashes.

Where it matters in this project:
- Prometheus stores metric history files on disk.
- Loki stores log chunks on disk.

---

### P95 Response Latency (Speed SLA)

What Latency is:
Latency = how long it takes for the app to reply to a customer request.

What P95 means:
> Imagine 100 customers walked into a bank. You measure how long each waited.
> P95 = the waiting time of customer 95 when sorted from fastest to slowest.
> So 95 out of 100 customers waited LESS than the P95 time.

If P95 = 1.8 seconds: 95 of 100 customers got their loan price in under 1.8 seconds.

Why we use P95 instead of Average:

| Metric | Problem |
|---|---|
| Average | 5 slow requests (10s each) + 95 fast (0.1s each) = average 0.6s. Looks fine! But 5 customers had a terrible experience. |
| P95 | Directly shows what 95% of customers experience. Catches outliers. |

The PromQL query:
  histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

Banking SLA: P95 must stay below 2.0 seconds at all times.
If P95 > 2.0s for 5 minutes, an alert fires and an engineer must investigate.

---

### Error Logs - Reading Application Logs

What Error Logs Are:
Every time something goes wrong, the app writes an ERROR-level log line describing
exactly what failed. These are critical for debugging.

Log Levels (from least to most severe):

| Level | When it is used | Example |
|---|---|---|
| DEBUG | Detailed internal state (development only) | "Entering validateEligibility method" |
| INFO | Normal successful operations | "Pricing calculated: CUST001234, 8.00%" |
| WARN | Unexpected but recoverable | "Credit score near minimum threshold: 560" |
| ERROR | Something failed - action required | "Database connection timed out after 30s" |

How to find Error Logs in Grafana:
1. Open Grafana: http://localhost:3000
2. Click Explore (compass icon on left sidebar)
3. Select Loki as the data source
4. Enter this query:
   {application="epricing-service"} | json | level="ERROR"
5. You will see a list of all ERROR log lines.

Common Errors and What They Mean:

| Error Message | What it means | What to do |
|---|---|---|
| "InsufficientCreditScore: 450 below 550" | Customer credit score too low | Normal rejection. No action needed. |
| "LoanAmountExceedsEligibility" | Income cannot support this loan | Normal rejection. No action needed. |
| "DataAccessException: Connection refused" | Database is down | Restart database, check disk space |
| "OtlpExporter: Connection refused" | OTel Collector not running | docker compose up otel-collector |
| "OutOfMemoryError: Java heap space" | App ran out of RAM | Increase heap size, find memory leak |

---

### HikariCP - The Database Connection Pool

What a Database Connection is:
Every time the app reads or writes to the database, it needs a connection (like a
phone line to call the database). Opening a new connection takes 50-100 milliseconds.

What Connection Pooling is:
> Instead of dialing a new phone call every time, keep 10 phone lines always open
> and ready. When you need the database, just pick up one of the already-open lines.

HikariCP is the connection pool manager built into Spring Boot. It keeps a pool of
pre-opened database connections so each request does not wait 100ms to connect.

The Metrics:
| Metric | What it shows |
|---|---|
| hikaricp_connections_active | How many connections are being used right now |
| hikaricp_connections_idle | How many connections are available and waiting |
| hikaricp_connections_pending | How many requests are WAITING for a connection |
| hikaricp_connections_max | Maximum pool size (our config: 10 connections) |

Simple Rule:
- connections_pending > 0 = Pool is EXHAUSTED. Requests are queuing.
  Fix: Increase spring.datasource.hikari.maximum-pool-size in application.yml.

---

> SECTION 13 SUMMARY: Manager's Assignment Checklist
>
> | Manager's Requirement | Metric Name | Where in Grafana |
> |---|---|---|
> | CPU Utilisation | process_cpu_usage | Top-left gauge |
> | RAM Utilisation | jvm_memory_used_bytes | Top-center gauge |
> | Disk Utilisation | disk_free_bytes | Top-right gauge |
> | Application Logs | Loki: level="ERROR" | Bottom log panel |
> | SLA Latency | histogram_quantile(0.95,...) | Latency line graph |
> | GC Pauses | jvm_gc_pause_seconds | JVM memory panel |
> | DB Connection Pool | hikaricp_connections_active | DB panel |

---

## 14. How to Start and Use This System

### Step 1: Make Sure Docker is Running

Open Docker Desktop and check the Docker whale icon is visible in the system tray.

### Step 2: Start Everything

Open PowerShell in the project folder (c:\Users\Sonux\Desktop\epricing-service):

  docker compose up -d

This starts all 7 services in the background. The -d means "detached" (runs in background).
Wait about 60 seconds for Spring Boot to fully start.

### Step 3: Check All Services Are Running

  docker compose ps

You should see all 7 services with status "Up":

  NAME                STATUS
  epricing-service    Up (healthy)
  prometheus          Up
  grafana             Up (healthy)
  loki                Up
  promtail            Up
  otel-collector      Up
  traffic-generator   Up

### Step 4: Open Grafana

1. Go to: http://localhost:3000
2. Login: admin / Bank@grafana123
3. Click Dashboards -> ePricing Complete Observability Dashboard

You will see live graphs updating every 10 seconds automatically.

### Step 5: Test the API Manually (Optional)

Open PowerShell and run:

  $body = @{
      customer_id = "CUST999888"
      product_type = "HOME_LOAN"
      loan_amount = 5000000
      loan_tenure_months = 240
      credit_score = 780
      annual_income = 2400000
  } | ConvertTo-Json

  Invoke-RestMethod -Uri "http://localhost:8080/api/v1/pricing" `
      -Method Post -Body $body -ContentType "application/json"

### Step 6: Stop Everything

  docker compose down

Data in volumes is preserved. Grafana dashboards and Prometheus history remain safe.

---

## 15. Common Mistakes and How to Fix Them

### Problem: "Grafana shows No Data on all panels"

Cause: Prometheus is not scraping the app.

Check: Go to http://localhost:9090/targets and look if epricing-service is green.
If it is red, the app is not reachable.

Fix: Run docker compose ps to make sure epricing-service is running.

---

### Problem: "docker compose up fails: port already in use"

Cause: Another Java process is already running on port 8080.

Fix:
  Stop-Process -Name java -Force
  docker compose up -d

---

### Problem: "Grafana login says invalid credentials"

Cause: Container lost the password after a forced recreation.

Fix:
  docker exec -it grafana grafana-cli admin reset-admin-password Bank@grafana123
  docker restart grafana

---

### Problem: "Loki logs panel shows no logs"

Cause: Promtail has not shipped logs yet. Loki may have just started.

Fix: Wait 30 seconds. Then in Grafana Explore, run:
  {application="epricing-service"}

---

### Problem: "P95 latency is always very high (> 2 seconds)"

Cause: The simulate-slow endpoint in the traffic-generator artificially adds latency
every 2 seconds. This is INTENTIONAL for the demo - it shows how alerts work.

---

## 16. Quick Reference Summary

### All URLs at a Glance

| Service | URL | Username | Password |
|---|---|---|---|
| Grafana (main dashboard) | http://localhost:3000 | admin | Bank@grafana123 |
| Prometheus (query metrics) | http://localhost:9090 | - | - |
| ePricing API (loan pricing) | http://localhost:8080/api/v1/pricing | - | - |
| Actuator Health | http://localhost:8081/actuator/health | - | - |
| Prometheus Targets | http://localhost:9090/targets | - | - |
| Raw Metrics (text) | http://localhost:8081/actuator/prometheus | - | - |

### One-Line Tool Summary

| Tool | One Line |
|---|---|
| epricing-service | Java app that calculates loan rates and EMIs |
| Prometheus | Pulls numbers from app every 10 seconds, stores them by time |
| Grafana | Draws graphs from Prometheus and Loki data |
| Loki | Stores text log lines indexed by labels |
| Promtail | Reads log files from disk, ships them to Loki |
| OpenTelemetry | Tracks every request's journey with a traceId |
| traffic-generator | Robot that sends fake loan requests to keep graphs alive |

### Key Metric Names

| Metric Name | Type | What it measures |
|---|---|---|
| process_cpu_usage | Gauge | CPU % used by Java |
| jvm_memory_used_bytes{area="heap"} | Gauge | Heap RAM used |
| jvm_memory_used_bytes{area="nonheap"} | Gauge | Non-heap RAM used |
| jvm_gc_pause_seconds_count | Counter | GC pause events count |
| pricing_requests_total | Counter | Total loan pricing requests |
| http_server_requests_seconds_bucket | Histogram | Request latency distribution |
| hikaricp_connections_active | Gauge | DB connections in use |
| hikaricp_connections_pending | Gauge | Requests waiting for a DB connection |

### Commands Cheat Sheet

  # Start everything
  docker compose up -d

  # Check status of all 7 services
  docker compose ps

  # View logs of one service
  docker compose logs epricing-service

  # Follow live logs in real-time
  docker compose logs -f epricing-service

  # Stop everything (keeps data in volumes)
  docker compose down

  # Stop and delete ALL data (fresh start)
  docker compose down -v

  # Restart one specific service
  docker compose restart grafana

  # Run manual traffic simulation from your terminal
  .\simulate_traffic.ps1

---

You made it to the end!

You now understand:
- What every single tool in this project does
- How a loan request flows through the entire system step by step
- What every metric on the Grafana dashboard means in plain English
- How to start, use, and troubleshoot the system

This is everything you need to present confidently to your manager.
