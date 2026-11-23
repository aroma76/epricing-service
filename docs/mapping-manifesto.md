# Enterprise Banking Analytics Schema Mapping Manifesto
## Source-to-Target Data Transformation Plan: `epricing-service` -> Enterprise Data Lakehouse

**Document Identifier:** `EPRICING-MAP-MANIFESTO-V1`  
**Source System:** Bank ePricing Microservice (Transactional OLTP on YugabyteDB Distributed SQL)  
**Target Platform:** Enterprise Cloud Data Warehouse & Analytics Platform (Google Cloud BigQuery / Apache Iceberg Lakehouse)  
**Architecture Model:** Dimensional Star Schema (Kimball Methodology)  
**Status:** Approved for Implementation  

---

## 1. Executive Summary & Warehouse Architecture

### 1.1 Objective
This Mapping Manifesto establishes the definitive semantic and technical transformation rules for ingesting transactional OLTP loan pricing data (`pricing_requests`, `pricing_audit_logs`) and Kafka streaming events (`loan-pricing-events`) into an analytical star-schema. The target data model empowers risk modeling teams, executive treasury dashboards, regulatory reporting auditors (RBI/Basel III), and marketing analytics.

### 1.2 Target Star-Schema Architecture
```
                                 ┌────────────────────────┐
                                 │      dim_customer      │
                                 ├────────────────────────┤
                                 │ PK customer_key        │
                                 │    customer_id_masked  │
                                 │    customer_hash_token │
                                 │    credit_score_tier   │
                                 │    income_bracket      │
                                 └───────────┬────────────┘
                                             │ 1:N
                                             ▼
┌────────────────────────┐       ┌───────────────────────────────┐       ┌────────────────────────┐
│    dim_loan_product    │       │     fact_pricing_decisions    │       │        dim_date        │
├────────────────────────┤       ├───────────────────────────────┤       ├────────────────────────┤
│ PK product_key         │◄──────┤ PK  pricing_decision_key      │──────►│ PK date_key            │
│    product_type_code   │  1:N  │ FK  customer_key              │  N:1  │    calendar_date       │
│    product_display_name│       │ FK  product_key               │       │    fiscal_quarter      │
│    base_interest_rate  │       │ FK  date_key                  │       │    banking_day_flag    │
│    risk_multiplier     │       │     loan_amount               │       └────────────────────────┘
└────────────────────────┘       │     interest_rate_pa          │
                                 │     emi_amount                │
                                 │     foir_ratio                │
                                 │     status_code               │
                                 │     processing_time_ms        │
                                 │     trace_id                  │
                                 └───────────────┬───────────────┘
                                                 │ 1:N
                                                 ▼
                                 ┌───────────────────────────────┐
                                 │       fact_audit_events       │
                                 ├───────────────────────────────┤
                                 │ PK  audit_event_key           │
                                 │ FK  pricing_decision_key      │
                                 │ FK  customer_key              │
                                 │ FK  date_key                  │
                                 │     action_code               │
                                 │     outcome_code              │
                                 │     duration_ms               │
                                 │     performed_by_system       │
                                 │     trace_id                  │
                                 └───────────────────────────────┘
```

---

## 2. Universal Data Cleanliness & Standardization Rules

Prior to loading into target dimensions and facts, all incoming transactional records must satisfy universal data hygiene invariants:

1. **Null Standardization:**
   - Any literal strings such as `"NULL"`, `"null"`, `"None"`, `"N/A"`, `""` (empty string), or whitespace-only values must be standardized to database `NULL`.
2. **String Trimming & Casing:**
   - All string identifiers, status enumerations, and codes must be trimmed of leading/trailing whitespace (`TRIM()`).
   - Categorical codes (`status`, `product_type`, `outcome`, `action`) must be coerced to uppercase (`UPPER()`).
3. **Temporal Normalization:**
   - Source timestamps in `TIMESTAMP WITHOUT TIME ZONE` (IST/Asia/Kolkata) must be converted into standard ISO-8601 UTC timestamps (`TIMESTAMP` in BigQuery / UTC microsecond precision).
4. **PII Masking & Privacy Compliance (RBI & DPDP Act 2023):**
   - Plaintext customer IDs (e.g., `CUST001234`) must never be stored in unmasked form in public analytics data marts.
   - Target schemas provide two views:
     - `customer_id_masked`: e.g., `CUST****1234` (for operational reporting).
     - `customer_hash_token`: SHA-256 HMAC hash using an enterprise salt key (for longitudinal cohort joins without revealing PII).
5. **Defensive Numeric Bounds:**
   - Financial amounts (`loan_amount`, `annual_income`, `emi_amount`) must be strictly cast to `NUMERIC(18, 2)` or `DECIMAL(18, 2)`.
   - Interest rates must be verified non-negative and cast to `NUMERIC(5, 2)`.
   - Credit scores must be bounded between `300` and `900`. Out-of-bounds credit scores must map to `NULL` with a data quality flag.

---

## 3. Detailed Target Table Mapping Specifications

### 3.1 Target Dimension: `dim_customer`
* **Target Table Description:** Type 2 Slowly Changing Dimension (SCD Type 2) tracking unique borrower risk profiles, income brackets, and compliance tokens over time.
* **Anchor Source Table:** `pricing_requests`
* **Contributing Source Tables:** `pricing_audit_logs` (for historical IP and interaction metadata)
* **Join Paths & Optimization:**
  - `pricing_requests` left-joined with `pricing_audit_logs` on `pricing_requests.customer_id = pricing_audit_logs.customer_id`.
  - De-duplicated on `customer_id` using `QUALIFY ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY created_at DESC) = 1` for current state.

#### Field-Level Mapping Specification: `dim_customer`
| Target Field | Target Type | Source Field / Logic | Mapping Type | Rationale / Transformation Details |
| :--- | :--- | :--- | :--- | :--- |
| `customer_key` | `INT64` | `FARM_FINGERPRINT(customer_id)` | Derived | Surrogate integer key generated via stable deterministic hash for performant star-schema joins. |
| `customer_id_masked` | `STRING` | `CONCAT(SUBSTR(customer_id, 1, 4), '****', SUBSTR(customer_id, -4))` | Derived | RBI regulatory masking ensuring zero plain-text PII in reporting layers. |
| `customer_hash_token` | `STRING` | `TO_HEX(SHA256(CONCAT(customer_id, '${SALT_SECRET}')))` | Derived | Cryptographic salted pseudonymized token for cross-table analytics joins. |
| `latest_credit_score` | `INT64` | `pricing_requests.credit_score` | Direct | Credit score from most recent underwriting inquiry. |
| `credit_score_tier` | `STRING` | `CASE WHEN credit_score >= 800 THEN 'EXCELLENT' WHEN credit_score >= 750 THEN 'VERY_GOOD' WHEN credit_score >= 700 THEN 'GOOD' WHEN credit_score >= 650 THEN 'FAIR' ELSE 'SUBPRIME' END` | Derived | Standard banking credit tier classification. |
| `latest_annual_income`| `NUMERIC(18,2)`| `pricing_requests.annual_income` | Direct | Annual borrower gross income. |
| `income_bracket` | `STRING` | `CASE WHEN annual_income >= 5000000 THEN 'HNI (>50L)' WHEN annual_income >= 2500000 THEN 'AFFLUENT (25L-50L)' WHEN annual_income >= 1000000 THEN 'MIDDLE_INCOME (10L-25L)' ELSE 'ENTRY (<10L)' END` | Derived | Standard income bracket categorization for portfolio segmentation. |
| `risk_category` | `STRING` | `pricing_requests.risk_category` | Direct | Standardized risk category (`LOW`, `MEDIUM`, `HIGH`). |
| `effective_start_date`| `TIMESTAMP` | `pricing_requests.created_at` | Derived | SCD Type 2 validity window start timestamp. |
| `effective_end_date` | `TIMESTAMP` | `NULL` | Gap / Default | SCD Type 2 validity window end timestamp (NULL for active record). |
| `is_current_flag` | `BOOLEAN` | `TRUE` | Derived | Active record indicator for SCD Type 2. |

---

### 3.2 Target Dimension: `dim_loan_product`
* **Target Table Description:** Dimension modeling loan offerings, risk multipliers, tenure limits, and regulatory interest rate bounds.
* **Anchor Source Table:** `pricing_requests` (Distinct `product_type`)
* **Contributing Configuration:** `application.yml` (`epricing.pricing.risk-multiplier`, `base-rate`)

#### Field-Level Mapping Specification: `dim_loan_product`
| Target Field | Target Type | Source Field / Logic | Mapping Type | Rationale / Transformation Details |
| :--- | :--- | :--- | :--- | :--- |
| `product_key` | `INT64` | `FARM_FINGERPRINT(UPPER(TRIM(product_type)))` | Derived | Surrogate primary key for loan product dimension. |
| `product_type_code` | `STRING` | `UPPER(TRIM(pricing_requests.product_type))` | Direct | Canonical product code (`HOME_LOAN`, `AUTO_LOAN`, `PERSONAL_LOAN`, `BUSINESS_LOAN`, `EDUCATION_LOAN`). |
| `product_display_name`| `STRING` | Formatted title string | Derived | E.g. `'Home Mortgage Loan'`, `'Automobile Vehicle Loan'`. |
| `product_category` | `STRING` | `CASE WHEN product_type IN ('HOME_LOAN', 'AUTO_LOAN') THEN 'SECURED' ELSE 'UNSECURED' END` | Derived | Banking regulatory collateral categorization. |
| `base_interest_rate` | `NUMERIC(5,2)` | `8.50` | Configuration | Reference bank benchmark lending rate (MCLR / Repo-linked). |
| `risk_multiplier` | `NUMERIC(4,2)` | `CASE product_type WHEN 'HOME_LOAN' THEN 1.00 WHEN 'EDUCATION_LOAN' THEN 1.05 WHEN 'AUTO_LOAN' THEN 1.10 WHEN 'BUSINESS_LOAN' THEN 1.30 WHEN 'PERSONAL_LOAN' THEN 1.50 ELSE 1.00 END` | Configuration | Product risk multiplier applied in calculation engine. |
| `min_tenure_months` | `INT64` | `12` | Rule | Minimum statutory loan tenure. |
| `max_tenure_months` | `INT64` | `CASE product_type WHEN 'HOME_LOAN' THEN 360 ELSE 84 END` | Rule | Maximum statutory loan tenure. |

---

### 3.3 Target Fact: `fact_pricing_decisions`
* **Target Table Description:** Core accumulation fact table recording every loan pricing quote calculation, underwriting parameters, interest computation, approval status, and execution performance.
* **Grain:** One row per pricing calculation request.
* **Anchor Source Table:** `pricing_requests`
* **Join Paths & Optimization:**
  - `pricing_requests` JOIN `dim_customer` ON `FARM_FINGERPRINT(pricing_requests.customer_id) = dim_customer.customer_key`
  - `pricing_requests` JOIN `dim_loan_product` ON `FARM_FINGERPRINT(pricing_requests.product_type) = dim_loan_product.product_key`
  - Partitioned by `DATE(created_at)`, clustered by `product_key`, `status_code`.

#### Field-Level Mapping Specification: `fact_pricing_decisions`
| Target Field | Target Type | Source Field / Logic | Mapping Type | Rationale / Transformation Details |
| :--- | :--- | :--- | :--- | :--- |
| `pricing_decision_key` | `INT64` | `pricing_requests.id` | Direct | Unique primary key corresponding to transaction record ID. |
| `customer_key` | `INT64` | `FARM_FINGERPRINT(customer_id)` | Joined | Foreign key referencing `dim_customer`. |
| `product_key` | `INT64` | `FARM_FINGERPRINT(UPPER(TRIM(product_type)))`| Joined | Foreign key referencing `dim_loan_product`. |
| `date_key` | `INT64` | `CAST(FORMAT_TIMESTAMP('%Y%m%d', created_at) AS INT64)` | Derived | Date integer key (e.g. `20261118`) referencing `dim_date`. |
| `created_timestamp` | `TIMESTAMP` | `pricing_requests.created_at` | Direct | Exact calculation timestamp normalized to UTC. |
| `loan_amount` | `NUMERIC(18,2)`| `pricing_requests.loan_amount` | Direct | Principal loan amount requested in INR. |
| `loan_tenure_months` | `INT64` | `pricing_requests.loan_tenure_months` | Direct | Repayment period in months. |
| `credit_score` | `INT64` | `pricing_requests.credit_score` | Direct | Bureau score evaluated at inquiry. |
| `annual_income` | `NUMERIC(18,2)`| `pricing_requests.annual_income` | Direct | Verified borrower gross annual income. |
| `calculated_rate_pa` | `NUMERIC(5,2)` | `pricing_requests.calculated_rate` | Direct | Quoted annualized interest rate. |
| `emi_amount` | `NUMERIC(18,2)`| `pricing_requests.emi_amount` | Direct | Monthly Equated Installment computed. |
| `total_payable_amount` | `NUMERIC(18,2)`| `pricing_requests.total_payable_amount` | Direct | Total payable principal + interest over loan lifetime. |
| `total_interest_amount`| `NUMERIC(18,2)`| `pricing_requests.total_interest_payable` | Direct | Total interest yield over loan lifetime. |
| `foir_ratio` | `NUMERIC(5,2)` | `ROUND((emi_amount * 12 / annual_income) * 100, 2)` | Derived | Fixed Obligation to Income Ratio metric critical for underwriting risk analysis. |
| `status_code` | `STRING` | `UPPER(TRIM(pricing_requests.status))` | Direct | Underwriting state (`CALCULATED`, `APPROVED`, `REJECTED`, `EXPIRED`, `ERROR`). |
| `error_message` | `STRING` | `pricing_requests.error_message` | Direct | Reason for rejection or technical exception message. |
| `processing_time_ms` | `INT64` | `pricing_requests.processing_time_ms` | Direct | Server-side execution latency for SLA tracking. |
| `request_ip_masked` | `STRING` | Mask last octet of `request_ip` | Derived | Compliance masking of caller IP (`172.20.xxx.xxx`). |
| `trace_id` | `STRING` | `pricing_requests.trace_id` | Direct | W3C OpenTelemetry distributed trace ID for correlating metrics, logs, and traces in Tempo. |

---

### 3.4 Target Fact: `fact_audit_events`
* **Target Table Description:** High-volume immutable ledger tracking all state transitions, approvals, human or workflow signals, and security audit access.
* **Grain:** One row per audit entry.
* **Anchor Source Table:** `pricing_audit_logs`
* **Join Paths & Optimization:**
  - `pricing_audit_logs` LEFT JOIN `pricing_requests` ON `pricing_audit_logs.pricing_request_id = pricing_requests.id`
  - Partitioned by `DATE(created_at)`, clustered by `action_code`, `outcome_code`.

#### Field-Level Mapping Specification: `fact_audit_events`
| Target Field | Target Type | Source Field / Logic | Mapping Type | Rationale / Transformation Details |
| :--- | :--- | :--- | :--- | :--- |
| `audit_event_key` | `INT64` | `pricing_audit_logs.id` | Direct | Unique audit event sequence identifier. |
| `pricing_decision_key` | `INT64` | `pricing_audit_logs.pricing_request_id` | Direct | Foreign key referencing `fact_pricing_decisions`. |
| `customer_key` | `INT64` | `FARM_FINGERPRINT(pricing_audit_logs.customer_id)` | Joined | Foreign key referencing `dim_customer`. |
| `date_key` | `INT64` | `CAST(FORMAT_TIMESTAMP('%Y%m%d', created_at) AS INT64)` | Derived | Date integer key referencing `dim_date`. |
| `event_timestamp` | `TIMESTAMP` | `pricing_audit_logs.created_at` | Direct | Exact audit record timestamp. |
| `action_code` | `STRING` | `UPPER(TRIM(pricing_audit_logs.action))` | Direct | Audit action identifier (`PRICING_CALCULATED`, `RATE_APPROVED`, `RATE_REJECTED`, `RATE_EXPIRED`, `TECHNICAL_FAILURE`). |
| `outcome_code` | `STRING` | `UPPER(TRIM(pricing_audit_logs.outcome))`| Direct | Result status (`SUCCESS`, `REJECTED`, `FAILED`). |
| `performed_by_system` | `STRING` | `pricing_audit_logs.performed_by` | Direct | Originator (`epricing-service`, `temporal-worker`, `audit-agent`). |
| `description` | `STRING` | `pricing_audit_logs.description` | Direct | Human-readable audit narrative. |
| `duration_ms` | `INT64` | `pricing_audit_logs.duration_ms` | Direct | Elapsed duration of operation in milliseconds. |
| `user_agent_short` | `STRING` | Cleaned user agent string | Derived | Extracted client application or HTTP client identity. |
| `trace_id` | `STRING` | `pricing_audit_logs.trace_id` | Direct | OpenTelemetry trace ID linked to parent business span. |

---

### 3.5 Streaming Event Ingestion Mapping: `LoanPricingEvent` JSON -> Lakehouse Facts

Downstream streaming pipelines (e.g., Apache Beam / Google Cloud Dataflow, Spark Structured Streaming) ingest real-time events directly from the Kafka topic `loan-pricing-events`. The table below establishes the direct field transformation matrix from `LoanPricingEvent` JSON attributes into Silver/Gold lakehouse star-schema tables:

| `LoanPricingEvent` JSON Attribute | Target Table | Target Column | Target Data Type | Transformation & Enrichment Logic |
|---|---|---|---|---|
| `request_id` | `fact_pricing_decisions` | `pricing_decision_key` | `INT64` | Primary key identifier matching OLTP `pricing_requests.id`. |
| `customer_id` | `dim_customer` / `fact_pricing_decisions` | `customer_key` | `INT64` | `FARM_FINGERPRINT(customer_id)` surrogate key lookup into `dim_customer`. |
| `customer_id` | `dim_customer` | `customer_id_masked` | `STRING` | Sanitized PII string: `CUST****1234` generated via `maskCustomerId()`. |
| `customer_id` | `dim_customer` | `customer_hash_token` | `STRING` | Cryptographic SHA-256 salted hash for GDPR-compliant customer cohort joins. |
| `product_type` | `dim_loan_product` | `product_key` | `INT64` | Surrogate key lookup for product (`HOME_LOAN`, `PERSONAL_LOAN`, etc.). |
| `loan_amount` | `fact_pricing_decisions` | `loan_amount` | `NUMERIC(18,2)` | Principal loan amount in INR. |
| `interest_rate` | `fact_pricing_decisions` | `interest_rate_pa` | `NUMERIC(5,2)` | Quoted annual percentage interest rate. |
| `emi_amount` | `fact_pricing_decisions` | `emi_amount` | `NUMERIC(18,2)` | Calculated Equated Monthly Installment. |
| `loan_tenure_months` | `fact_pricing_decisions` | `loan_tenure_months`| `INT64` | Loan tenure in months. |
| `total_payable_amount` | `fact_pricing_decisions`| `total_payable_amount` | `NUMERIC(18,2)` | Total repayment figure (Principal + Total Interest). |
| `total_interest_payable` | `fact_pricing_decisions`| `total_interest_amount`| `NUMERIC(18,2)` | Total interest yield over loan lifetime. |
| `risk_category` | `fact_pricing_decisions` | `risk_category` | `STRING` | Risk classification (`LOW`, `LOW_MEDIUM`, `MEDIUM`, `HIGH`). |
| `status` | `fact_pricing_decisions` | `status_code` | `STRING` | Lifecycle state (`CALCULATED`, `APPROVED`, `REJECTED`, `EXPIRED`). |
| `status` | `fact_audit_events` | `action_code` | `STRING` | When status is `APPROVED`, `REJECTED`, or `EXPIRED`, also appends an entry to `fact_audit_events`. |
| `timestamp` | `fact_pricing_decisions` / `fact_audit_events` | `created_timestamp` | `TIMESTAMP` | ISO-8601 millisecond string converted to UTC `TIMESTAMP`. |
| `trace_id` | `fact_pricing_decisions` / `fact_audit_events` | `trace_id` | `STRING` | W3C 32-character hex trace ID correlating OLTP transactions with Tempo traces. |

---

## 4. Join Topology & Scalability Plan

### 4.1 Join Scale Classification
| Join Path | Left Table | Right Table | Cardinality | Strategy | Optimization |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `J1` | `fact_pricing_decisions` | `dim_loan_product` | N : 1 | Large-to-Small | **Broadcast Join** (in-memory replicated dimension, ~5 products). |
| `J2` | `fact_pricing_decisions` | `dim_customer` | N : 1 | Large-to-Large | **Hash Join** partitioned by customer hash token. |
| `J3` | `fact_audit_events` | `fact_pricing_decisions` | N : 1 | Large-to-Large | Co-located partition join on `DATE(created_at)`. |

### 4.2 Deduplication & Data Quality Invariants
1. **At-Least-Once Streaming Deduplication:**
   - Both Kafka events and database replication can introduce duplicate event records during network rebalances.
   - ETL/ELT pipelines must apply `ROW_NUMBER() OVER (PARTITION BY pricing_decision_key ORDER BY created_timestamp DESC) = 1` before writing to analytical facts.
2. **Referential Integrity Enforcement:**
   - Where a `pricing_request_id` in `pricing_audit_logs` does not match an existing `pricing_requests` row (e.g. pre-underwriting validation failures rejected before DB row creation), `fact_audit_events.pricing_decision_key` must resolve to a sentinel key (`-1` / `'UNSAVED_REJECTION'`) with `dim_customer` populated from the audit log's `customer_id`.

---

## 5. ETL Pipeline Execution Roadmap

1. **Bronze Layer (Raw Ingestion):**
   - Direct streaming from Kafka topic `loan-pricing-events` and CDC from YugabyteDB via Debezium into raw append-only Lakehouse tables.
2. **Silver Layer (Cleaned & Standardized):**
   - Applies Universal Data Cleanliness Rules, PII masking, UTC temporal alignment, and deduplication.
3. **Gold Layer (Star-Schema Data Mart):**
   - Materializes `dim_customer`, `dim_loan_product`, `fact_pricing_decisions`, and `fact_audit_events` on daily/hourly micro-batches.
