# 📘 Simple & Easy Observability Guide

Welcome! This guide explains **how this project works** in plain, simple English without confusing jargon.

---

## 🍔 1. The Simple Analogy: How This System Works

Think of this whole project like a **Fast-Food Restaurant**:

```
[ Customer ] ──► [ Cashier / Chef ] ──► [ Cash Register ] ──► [ Security Camera ] ──► [ Manager's TV Screen ]
 (User Request)    (Spring Boot App)     (Prometheus Metrics)    (Loki Logs)          (Grafana Dashboard)
```

1. **Customer (`traffic-generator`)**: Places loan orders every 2 seconds.
2. **Chef (`epricing-service`)**: Cooks the order (calculates interest rates and EMIs).
3. **Cash Register (`Prometheus`)**: Counts how many orders were made, how fast they were cooked, and how much money/RAM was used.
4. **Security Camera (`Promtail + Loki`)**: Records every single event and error line on tape.
5. **Manager's TV Screen (`Grafana`)**: Displays live graphs and cameras on one screen so the manager can see if the restaurant is running smoothly!

---

## 🔄 2. How Data Flows (Step-by-Step)

```
Step 1: Traffic Generator sends a loan request to Spring Boot App (:8080)
           │
Step 2: Spring Boot App calculates the interest rate and EMI
           │
Step 3: Spring Boot App creates 3 things automatically:
           ├─ 📊 Numbers (Metrics)  ──► Pulled by Prometheus every 15 seconds
           ├─ 📝 Logs (JSON text)  ──► Read by Promtail and sent to Loki
           └─ 🧭 Tracing (traceId) ──► Sent to OpenTelemetry Collector
           │
Step 4: Grafana fetches data from Prometheus & Loki and draws live graphs!
```

---

## 🛠️ 3. What Each Docker Tool Does (In 1 Sentence)

| Tool Name | What it does in 1 sentence |
|---|---|
| **epricing-service** | The main Java app that calculates loan rates and EMIs. |
| **Prometheus** | The numbers collector that saves CPU, RAM, and request counts over time. |
| **Grafana** | The visual UI dashboard with graphs, gauges, and charts (`http://localhost:3000`). |
| **Loki** | The log vault that stores error lines and log text. |
| **Promtail** | The log delivery truck that picks up log files from disk and delivers them to Loki. |
| **OpenTelemetry (OTel)** | The GPS tracker that attaches a unique `traceId` to every customer request. |
| **traffic-generator** | The automated robot that sends fake loan requests to keep graphs moving live. |

---

## 📊 4. What Each Metric Means (Your Manager's Assignment List)

### 🖥️ 1. CPU Utilisation (`process_cpu_usage`)
- **What it means**: How hard the CPU chip is working (0% to 100%).
- **Why we care**: If CPU goes above **80%**, the server gets too hot/busy, causing customer requests to freeze.

### 🧠 2. RAM / Memory Utilisation (`jvm_memory_used_bytes`)
- **What it means**: How much memory (RAM) Java is using to hold temporary data.
- **Why we care**: If RAM hits **100%**, Java runs out of memory and crashes (`OutOfMemoryError`).

### 💾 3. Disk Utilisation (`diskSpace_free_bytes`)
- **What it means**: How much hard drive space is left on the server.
- **Why we care**: If disk space hits **0MB**, the database cannot save new records.

### ⏱️ 4. P95 Response Latency (Speed SLA)
- **What it means**: 95% of customer requests are faster than this response time.
- **Why we care**: Banking rules require response times to be **under 2.0 seconds**.

### 🪵 5. Error Logs (Loki)
- **What it means**: Text records of what went wrong (e.g. `Invalid credit score`, `Database connection error`).
- **Why we care**: Helps engineers fix bugs instantly by searching by `traceId` or `customerId`.

---

## 🚀 5. How to Run & Present to Your Manager

### Step 1: Start Everything with 1 Command
Open PowerShell in your project folder and run:
```powershell
docker-compose up -d
```

### Step 2: Open Grafana in Your Browser
Open: **[http://localhost:3000](http://localhost:3000)**
- **Username**: `admin`
- **Password**: `Bank@grafana123`

### Step 3: Open the Dashboard
Click **Dashboards** (left menu) ➔ **`ePricing Complete Observability Dashboard`**.

*(You will see all your graphs, gauges, CPU, RAM, and logs updating automatically!)*
