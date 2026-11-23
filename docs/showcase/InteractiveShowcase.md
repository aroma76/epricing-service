# Interactive Architectural UI Showcase

## 1. Overview & Purpose

The `epricing-service` repository includes a dedicated browser-based interactive showcase located in the `showcase/` directory. This frontend experience provides an intuitive visual demonstration of the ePricing platform's core architectural capabilities:

- **Real-Time Loan Pricing Engine**: Interactive calculator demonstrating EMI, interest breakdown, and risk-tier rate adjustments.
- **Temporal Durable Workflow Simulator**: Visual state machine illustrating quote generation, 7-day rate-lock retention, and asynchronous approval/rejection signals.
- **Kafka Event Streaming Inspector**: Live JSON visualizer showing emitted `LoanPricingEvent` payloads and schema metadata.
- **Observability Waterfall**: Simulated multi-span distributed trace highlighting correlation across HTTP, Temporal activities, and Kafka publication.
- **Ultra-Modern Glassmorphism UI**: Cutting-edge visual design system showcasing enterprise financial technology aesthetics.

```
showcase/
├── index.html            # Main multi-tab interactive showcase application
└── glassmorphism.html    # Standalone design system showcase and component sandbox
```

---

## 2. Core Interactive Features

### 2.1 Real-Time Loan Pricing Sandbox
The pricing calculator provides sliders and input controls allowing users to adjust loan parameters:
- **Principal Loan Amount**: Adjustable from ₹50,000 to ₹10,00,00,000.
- **Tenure**: Adjustable from 6 to 360 months (up to 30 years).
- **Product Type**: Instant toggle between Home Loan, Personal Loan, Auto Loan, and Education Loan.
- **Customer Credit Tier**: Dynamically adjust risk profile (Excellent, Good, Average, Poor) to view real-time interest rate margin deltas.

**Output Visualizations**:
- Real-time calculation of **Monthly EMI**, **Total Interest Payable**, and **Total Amount Payable**.
- Interactive SVG amortization donut chart illustrating principal vs. interest breakdown.
- Dynamic risk indicator displaying computed risk category (`LOW`, `LOW_MEDIUM`, `MEDIUM`, `HIGH`).

### 2.2 Temporal Durable Rate-Lock Workflow Visualizer
Simulates the 7-day durable rate-lock lifecycle orchestrated by Temporal:
1. **Quoted State**: Starts a simulated `LoanPricingWorkflow` instance with a unique workflow ID and 7-day expiration countdown.
2. **Signal Controls**:
   - **Approve Quote**: Triggers customer approval signal, transitioning state to `APPROVED` and triggering downstream Kafka event emission.
   - **Reject Quote**: Triggers customer rejection with customizable rejection reason, transitioning state to `REJECTED`.
   - **Fast-Forward Expiration**: Simulates Temporal durable timer advancing 7 days, transitioning state to `EXPIRED`.
3. **Activity Execution Log**: Visualizes sequential activity execution (`calculateAndPersistPricing`, `recordQuoteApproval`, etc.) with simulated retry handling.

### 2.3 Kafka Domain Event Streaming Inspector
Whenever a pricing decision is calculated or a workflow state changes, the showcase displays the emitted `LoanPricingEvent`:
- Syntax-highlighted JSON viewer with copy-to-clipboard functionality.
- Key inspection highlighting:
  - Kafka Partition Key (`customer_id`).
  - W3C Distributed Trace Context (`trace_id` and simulated `traceparent` header).
  - Dual snake_case / camelCase compatibility annotations.

### 2.4 Distributed Tracing Waterfall
Renders a simulated Grafana Tempo trace waterfall showing:
- Parent span: `HTTP POST /api/v1/pricing` (25ms)
- Child span 1: `PricingService.calculate` (8ms)
- Child span 2: `PricingRepository.save` (12ms)
- Child span 3: `kafka.publish.loan-pricing-events` (3ms)

### 2.5 Interactive Data Flow Architecture & Animated Packet Controls
The primary showcase tab (`index.html`) features a real-time interactive canvas with filter controls that visually isolate and demonstrate how data moves through the architecture:
- **`All Flows`**: Shows the complete 5-tier topology with all network connections, port bindings, and protocols active simultaneously.
- **`1. Underwriting (REST)`**: Isolates the synchronous online pricing flow: Client Ingress $\to$ `MDCFilter` $\to$ `PricingController` $\to$ `PricingCalculator` $\to$ `PricingAuditService` $\to$ YugabyteDB.
- **`2. Rate-Lock (gRPC)`**: Isolates Temporal durable orchestration: `TemporalPricingController` $\to$ Temporal Server (`:7233`) $\to$ Worker activity execution $\to$ 7-day rate guarantee state machine $\to$ Signal handlers.
- **`3. Events (Kafka)`**: Isolates asynchronous domain streaming: `KafkaPricingEventProducer` $\to$ KRaft topic `loan-pricing-events` (`:29092`) $\to$ BigQuery/Iceberg Lakehouse dimensional star schema.
- **`4. SRE Mesh (OTel)`**: Isolates full-stack observability pipelines: Actuator (`:8089`) $\to$ Prometheus (`:9090`), Logback JSON $\to$ Promtail $\to$ Loki (`:3100`), and OTel spans (`:4318`) $\to$ Tempo (`:3200`).
- **Animated SVG Data Packets (`Packets: ON/OFF`)**: Toggleable CSS stroke-dashoffset animation simulating real-time transaction payloads traveling across network lines between tiers.

---

## 3. Design System & Glassmorphism Aesthetics

The showcase (`glassmorphism.html` and `index.html`) implements a modern dark glassmorphic financial UI design system:

```css
/* Core Design Tokens */
--bg-primary: #0a0e17;
--bg-secondary: #111827;
--glass-surface: rgba(255, 255, 255, 0.04);
--glass-surface-hover: rgba(255, 255, 255, 0.08);
--glass-border: rgba(255, 255, 255, 0.08);
--glass-border-glow: rgba(0, 229, 255, 0.4);
--accent-cyan: #00e5ff;
--accent-purple: #7928ca;
--accent-emerald: #10b981;
--text-primary: #f9fafb;
--text-muted: #9ca3af;
```

### Visual Characteristics:
1. **Frosted Glass Blur**: Uses CSS `backdrop-filter: blur(16px)` to create translucent surfaces over dark mesh gradient backgrounds.
2. **Neon Border Glows**: Subtle neon accent borders that illuminate on hover and during active calculation transitions.
3. **Smooth Micro-Interactions**: CSS transitions on button clicks, slider drags, and modal transitions.
4. **Responsive Grid**: Fully responsive layout adapting from 4K desktop displays down to mobile devices.

---

## 4. Local Launch Instructions

The showcase is built using pure, standards-compliant HTML5, CSS3, and JavaScript, requiring zero npm dependencies, build tools, or webpack compilation.

### Option 1: Direct Browser Launch (Simplest)
On Windows, run in PowerShell:
```powershell
# Open main showcase in default browser
Start-Process "showcase\index.html"

# Open glassmorphism design gallery
Start-Process "showcase\glassmorphism.html"
```
Or simply double-click `showcase/index.html` in Windows Explorer or macOS Finder.

### Option 2: Local HTTP Server (Python)
To test cross-origin API integration with a running backend:
```bash
# From the project root
python -m http.server 8000
```
Then navigate to:
- Main Showcase: `http://localhost:8000/showcase/index.html`
- Design Gallery: `http://localhost:8000/showcase/glassmorphism.html`

### Option 3: Node / npx serve
```bash
npx serve showcase
```

---

## 5. Live Backend Integration vs. Simulation Mode

The showcase operates in two modes:

1. **Simulation Mode (Default)**:
   - Requires zero backend containers running.
   - All loan calculation formulas (reducing balance EMI, risk margins) execute locally in JavaScript client-side code matching `PricingCalculator.java`.
   - Perfect for demonstrations, UI evaluation, and offline architecture reviews.

2. **Live Backend Mode**:
   - When the Spring Boot microservice is running on port 8080 (`docker-compose up -d`), users can toggle the "Live API" switch in the UI header.
   - The showcase dispatches fetch calls directly to `http://localhost:8080/api/v1/pricing` and `http://localhost:8080/api/v1/workflow/pricing/start`.
   - Requires setting `CORS_ALLOWED_ORIGINS=http://localhost:8000,http://127.0.0.1:8000` or opening via the same origin.
