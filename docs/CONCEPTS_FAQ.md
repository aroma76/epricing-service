# ePricing Service — Simple Concepts Guide

> Written in plain language. No confusing jargon. Real-world examples used throughout.

---

## Table of Contents

1. [What does each file do?](#1-what-does-each-file-do)
2. [What is HikariCP?](#2-what-is-hikaricp)
3. [Does HikariCP connect the Simulator to OpenTelemetry?](#3-does-hikaricp-connect-the-simulator-to-opentelemetry)
4. [What is H2 Database?](#4-what-is-h2-database)
5. [Is H2 Database the same as Garbage Collection?](#5-is-h2-database-the-same-as-garbage-collection)
6. [Can I actually see what's stored in H2?](#6-can-i-actually-see-whats-stored-in-h2)
7. [Why is H2 storage small and dynamic?](#7-why-is-h2-storage-small-and-dynamic)
8. [How does the whole system work — step by step?](#8-how-does-the-whole-system-work--step-by-step)
9. [What are Micrometer Lightweight Counters?](#9-what-are-micrometer-lightweight-counters)
10. [Is Prometheus storage also dynamic?](#10-is-prometheus-storage-also-dynamic)
11. [Prometheus runs in Docker — so does it use my laptop's disk?](#11-prometheus-runs-in-docker--so-does-it-use-my-laptops-disk)
12. [Quick cheat sheet — where is each thing stored?](#12-quick-cheat-sheet--where-is-each-thing-stored)

---

## 1. What does each file do?

Think of this project like a **bank branch**. Different people have different jobs.
The files in this project work the same way — each file has one specific job.

### The Front Desk (API Layer — who talks to customers)

| File | Simple Explanation |
| :--- | :--- |
| `EpricingServiceApplication.java` | The **main switch** that turns the bank branch on. Everything starts here. |
| `PricingController.java` | The **front desk** that receives loan requests from customers and sends back the calculated loan offer. |
| `MetricsDemoController.java` | A **practice drill button** — you press it to simulate busy traffic, errors, or slow operations to test if your dashboards are working. |
| `HealthController.java` | A **"Are you open?" sign** — returns YES (UP) or NO (DOWN) when you check if the service is running. |

### The Back Office (Business Logic — who does the actual calculations)

| File | Simple Explanation |
| :--- | :--- |
| `PricingService.java` | The **branch manager** — coordinates everything: receives the loan request, calls the calculator, saves the result, records activity. |
| `PricingCalculator.java` | The **finance calculator** — does the actual math: interest rate, EMI amount, risk category, income check (FOIR). |
| `PricingAuditService.java` | The **compliance officer** — writes down a record of every decision made, for legal/audit purposes. |

### The Filing Room (Data Storage — who stores and retrieves records)

| File | Simple Explanation |
| :--- | :--- |
| `PricingRequest.java` | The **paper form** that gets filed — it defines every field stored for each loan calculation (customer ID, amount, rate, EMI, risk...). |
| `PricingRepository.java` | The **filing clerk** — knows how to save a form, find all forms for a customer, or fetch a single form by ID. |
| `PricingRequestDto.java` | The **intake form** a customer fills in — what they send when requesting a loan quote. |
| `PricingResponseDto.java` | The **quote letter** sent back to the customer — what the bank responds with. |

### The Security Cameras & Alarms (Monitoring Layer — who watches everything)

| File | Simple Explanation |
| :--- | :--- |
| `PricingMetrics.java` | The **tally counter** — keeps score of how many requests came in, how fast they were processed, how many failed. |
| `MDCFilter.java` | The **name tag machine** — every incoming request gets a unique ID tag (`traceId`) that gets stamped on every log line so you can track it. |
| `StructuredLogger.java` | The **event journalist** — writes structured log messages in a standard format so Grafana Loki can search them. |
| `OpenTelemetryConfig.java` | The **CCTV setup** — configures how traces (step-by-step journey recordings of each request) are sent to Grafana Tempo. |
| `GlobalExceptionHandler.java` | The **complaints desk** — when something breaks, this catches the error and returns a neat, readable error message instead of a crash. |

---

## 2. What is HikariCP?

### Simple Analogy: A Shared Taxi Fleet

Imagine your office has 10 shared taxis parked outside. When an employee needs to go somewhere,
they take one of the 10 taxis, do their trip, and return it to the fleet.

- **Without taxis:** Every employee has to order an Uber from scratch. That takes 5–10 minutes.
- **With taxis:** An employee grabs a ready taxi instantly and returns it when done.

**HikariCP works exactly the same way — but for database connections.**

- Opening a fresh database connection takes **50–100 milliseconds** every time.
- HikariCP keeps **10 connections already open** (the "taxis") so any request can grab one instantly.
- After the request finishes, the connection goes back to the pool, ready for the next request.

### Settings in This Project

| Setting | Value | What It Means |
| :--- | :--- | :--- |
| `pool-name` | `epricing-pool` | Name that appears in Prometheus graphs |
| `maximum-pool-size` | `10` | Maximum 10 taxis (connections) at one time |
| `minimum-idle` | `5` | Always keep at least 5 taxis waiting, even when quiet |
| `connection-timeout` | `30 seconds` | If all taxis are busy, wait max 30 seconds before giving up |

### What Happens When All Connections Are Busy?

If all 10 connections are in use and a new request arrives, that request **waits in a queue**.
You can see this in Grafana:
- `hikaricp_connections_pending > 0` means requests are waiting — the pool is exhausted.

---

## 3. Does HikariCP connect the Simulator to OpenTelemetry?

**No. These are two completely different things.**

| Component | What It Actually Does |
| :--- | :--- |
| **HikariCP** | Only connects Spring Boot to the **H2 Database** (for saving/reading loan records). Nothing to do with OTel. |
| **OpenTelemetry (OTel)** | Records the step-by-step journey of each request (Controller → Service → Database) and sends those "journey recordings" to Grafana Tempo. |
| **Traffic Simulator** | Sends fake loan requests to the API to generate traffic. As a side-effect, both H2 database queries (via HikariCP) AND trace spans (via OTel) get triggered automatically. |

Think of it this way:
- **HikariCP** = the pipe between your app and the **database filing cabinet**
- **OpenTelemetry** = the CCTV camera recording the **journey of each request**

They are both running at the same time but doing completely different jobs.

---

## 4. What is H2 Database?

### Simple Analogy: A Whiteboard in the Office

Imagine instead of a full filing cabinet room, your team uses a **whiteboard** to track today's
loan requests during working hours. It's fast, easy to write on, and everyone can read it instantly.
But when the office closes at the end of the day — the whiteboard gets erased.

**H2 Database works exactly like that whiteboard:**

- It's a **real SQL database** — you can run `SELECT`, `INSERT`, `UPDATE`, `DELETE` queries.
- It runs **inside the application's RAM memory** — no separate database server to install.
- It's **extremely fast** — reading and writing takes nanoseconds (no disk access needed).
- It **disappears when the app restarts** — just like a whiteboard gets erased at the end of the day.

### Why Is H2 Used Here?
This project is for **learning and observability demonstration**. Using H2 means:
- No need to install PostgreSQL or MySQL.
- The app starts instantly.
- You still get real database metrics, JPA behavior, and HikariCP pool activity to observe in Grafana.

In a **real bank production system**, H2 would be replaced by **PostgreSQL** or **Oracle**
which store data permanently on secure disk drives.

---

## 5. Is H2 Database the same as Garbage Collection?

**No. They are completely different things.**

| Term | Simple Explanation |
| :--- | :--- |
| **H2 Database** | A structured storage system (like a spreadsheet) for saving loan data using SQL. |
| **Garbage Collection (GC)** | Java's automatic memory cleaner — it looks for unused objects in RAM and deletes them to free up memory space. |

### How They Relate
Because H2 stores data in RAM (the JVM Heap), the Garbage Collector is technically
managing the same memory space. But:
- **H2** decides **what data to keep** (loan records while the app is running).
- **GC** cleans up **temporary objects** that are no longer needed (like short-lived HTTP request objects).

In Grafana, you can watch both happening live:
- **HikariCP panel** = database connection activity
- **JVM Memory panel** = GC memory cleanup activity

---

## 6. Can I actually see what's stored in H2?

**Yes! Two ways:**

### Way 1: Browser-Based SQL Console

Make sure the app is running, then open this link in your browser:

```
http://localhost:8080/api/v1/h2-console
```

On the login screen, fill in:

| Field | Type This Exactly |
| :--- | :--- |
| JDBC URL | `jdbc:h2:mem:epricingdb` |
| User Name | `epricing_user` |
| Password | `epricing_secret` |

> **Important:** Change the JDBC URL field — it defaults to `jdbc:h2:~/test` which is wrong.
> You must type `jdbc:h2:mem:epricingdb` instead.

Click **Connect**, then run:
```sql
SELECT * FROM PRICING_REQUESTS;
```

You will see all loan calculations stored as rows in a table.

### Way 2: REST API (Instant JSON Output)

Just open these links in your browser while the app is running:

```
http://localhost:8080/api/v1/pricing
http://localhost:8080/api/v1/pricing?customerId=CUST-101
http://localhost:8080/api/v1/pricing/1
```

You will get the stored data as JSON directly in your browser.

---

## 7. Why is H2 storage small and dynamic?

### It only grows when you add data
H2 does not reserve a fixed amount of RAM when the app starts.
It only uses memory when you actually insert rows. So:
- 0 loan requests stored = almost 0 memory used
- 1,000 loan requests stored = a few megabytes (each row is only ~500 bytes)
- 1,000,000 loan requests stored = a few hundred megabytes

### It shrinks again when data is removed
If rows are deleted from the table, the memory is freed back to the JVM.

### It resets when the app restarts
Because everything is in RAM:
- **App running** = all your data is there
- **App stopped** = whiteboard erased, data gone
- **App restarted** = fresh, empty database

This is **intentional for development and demos**. It keeps things clean and simple.

---

## 8. How does the whole system work — step by step?

Here is what happens from the moment the simulator sends a request to when you see it in Grafana:

```
YOU (or the Traffic Generator)
  │
  │  Sends a loan quote request:
  │  POST /api/v1/pricing
  │  { customerId: "CUST-101", loanAmount: 500000, tenure: 36 months }
  ▼
PricingController.java
  │  Receives the HTTP request
  │  MDCFilter stamps a unique traceId on everything
  ▼
PricingService.java
  │
  ├─► PricingCalculator.java
  │     Does the math:
  │     Base Rate (8.50%) + Risk Multiplier + Credit Score Adjustment
  │     Calculates EMI = ₹16,000/month
  │     Checks FOIR (monthly income must cover EMI)
  │
  ├─► PricingRepository.java (via HikariCP → H2 Database)
  │     Saves the full result as a row in the PRICING_REQUESTS table
  │     "Here is the loan offer we gave to CUST-101 at 9.5% rate"
  │
  └─► PricingMetrics.java (Micrometer)
        Increments a counter by +1
        Records how long the calculation took (e.g., 12ms)

Meanwhile, OpenTelemetry
  Recorded the entire journey above as a "trace"
  Exported it to Grafana Tempo via port 4318

Every 15 seconds, Prometheus
  Calls /actuator/prometheus
  Picks up the counter values (e.g., total_requests = 942)
  Stores them as a time-series entry with a timestamp

Grafana
  Reads from Prometheus (metrics), Tempo (traces), Loki (logs)
  Shows you live dashboards and alerts
```

---

## 9. What are Micrometer Lightweight Counters?

### Simple Analogy: A Tally Counter at a Stadium Gate

When fans enter a stadium, the gate attendant clicks a tally counter once for each person.
They do not write down each person's name, age, or ticket number — they just click: 1, 2, 3...

At the end of the event, you know **how many people entered** — but not who they were.

**Micrometer Counters work exactly the same way.**

Instead of storing the full loan calculation, Micrometer just increments a single number:

| What Happened | Micrometer Records |
| :--- | :--- |
| A loan request came in | `pricing_requests_total` = 943 (was 942, now +1) |
| A calculation took 12ms | `pricing_calculation_duration` records 12ms in a timer |
| 3 requests are being processed right now | `pricing_requests_active` = 3 (gauge) |

### Why "Lightweight"?
A counter is literally just **one 8-byte number** in memory.
Compare that to storing a full loan record (customer name, amount, rate, EMI, timestamps...) which
takes hundreds of bytes.

A counter can be incremented **millions of times per second** with no performance impact.
You are trading off detail ("who was the customer?") for speed and efficiency ("how many came?").

### Types of Micrometer Instruments

| Instrument | Goes Up Only? | Example Use |
| :--- | :--- | :--- |
| **Counter** | Yes (only goes up) | Count total requests, total errors |
| **Timer** | N/A (measures time) | How long each calculation takes |
| **Gauge** | Up and down | How many connections are active right now |
| **Distribution Summary** | N/A (tracks spread) | What loan amounts customers are requesting (small, medium, large?) |

---

## 10. Is Prometheus storage also dynamic?

**Yes! Prometheus storage grows and shrinks dynamically.**

### Simple Analogy: A CCTV Recording System

Imagine a CCTV system at a store. It:
1. Records footage continuously (every second)
2. Stores recordings on a hard drive
3. Automatically deletes footage older than 15 days to make room for new footage

**Prometheus works exactly the same way with your metric numbers.**

### Step by Step

**Step 1 — Scraping (Recording)**
Every 15 seconds, Prometheus visits your app's `/actuator/prometheus` page and reads the current
metric values. It stamps them with the current time:
```
10:45:00  →  pricing_requests_total = 120
10:45:15  →  pricing_requests_total = 125
10:45:30  →  pricing_requests_total = 132
```

**Step 2 — RAM Buffering (2-Hour Blocks)**
Prometheus keeps the latest 2 hours of data in RAM first (fast to write, fast to read).
After 2 hours, it compresses and moves the block to your local disk.

**Step 3 — Compression (90% Smaller)**
When writing to disk, Prometheus applies smart compression. Instead of storing the full number
each time, it only stores the **difference** from the previous value:
```
Instead of:  120, 125, 132, 140
It stores:   120, +5, +7, +8   ← much smaller!
```
This reduces storage by **over 90%**.

**Step 4 — Auto-Cleanup**
Old data is automatically deleted when it exceeds the retention limit:
- Default in this project: **15 days**
- If storage exceeds **5GB**, oldest data gets deleted automatically

So Prometheus grows as new data comes in, and shrinks when old data is auto-deleted. Fully dynamic.

---

## 11. Prometheus runs in Docker — so does it use my laptop's disk?

**Yes! It saves to your laptop's disk using Docker Volumes.**

### Simple Analogy: A USB Drive Plugged into a Virtual Machine

Even though Prometheus runs inside a Docker container (which is like a small isolated virtual box),
Docker "plugs in" a folder from your real laptop disk into that container. The container reads and
writes to your real disk through that connection.

This is called a **Docker Volume**.

```
Your Laptop's Real Disk
  └── /var/lib/docker/volumes/prometheus_data/
         ▲
         │  (Docker Volume — connected like a USB drive)
         │
  [Prometheus Container]
    └── /prometheus/   ← Prometheus thinks it's writing here
                          but it's actually writing to your real disk above
```

### What Gets Saved on Your Disk

| Container | What It Saves to Your Disk |
| :--- | :--- |
| **Prometheus** | All metric time-series history (TSDB blocks) |
| **Grafana** | Your dashboards, settings, and alert configurations |
| **Loki** | All structured log lines from the Spring Boot app |

**This means:** Even if you stop Docker and restart it tomorrow, all your Grafana dashboards
and Prometheus metric history will still be there — exactly as you left them.

Only the **H2 Database** (inside the Spring Boot app) gets wiped on restart, because it is
in RAM and not saved to a Docker Volume.

---

## 12. Quick Cheat Sheet — where is each thing stored?

| Type of Data | Example | Stored Where | Survives Restart? |
| :--- | :--- | :--- | :--- |
| Loan calculations | Customer ID, EMI, interest rate | H2 Database (RAM) | ❌ No — gone on restart |
| Metric counts | "942 requests processed" | Micrometer (RAM) | ❌ No — resets with app |
| Metric history over time | "At 10:45 there were 942 requests" | Prometheus TSDB (local disk via Docker) | ✅ Yes |
| Request journey traces | Controller → Service → DB waterfall | Grafana Tempo (Docker) | ✅ Yes |
| Log messages | "PRICING_COMPLETED for CUST-101" | Grafana Loki (Docker) + logs/ folder | ✅ Yes |

---

> **The One-Line Summary:**
>
> The **Spring Boot app** is the kitchen — it cooks (calculates) and stores today's orders (H2 DB) in RAM.
> The **observability stack** (Prometheus + Grafana + Loki) is the security system — it watches,
> records, and permanently saves everything that happened, even if the kitchen closes for the night.
