-- ═══════════════════════════════════════════════════════════════════════════
-- V4__seed_initial_data.sql
-- Baseline seed data for pricing calculations and audit trails
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO pricing_requests
    (customer_id, product_type, loan_amount, loan_tenure_months, credit_score, annual_income,
     total_payable_amount, total_interest_payable, risk_category, calculated_rate, emi_amount,
     status, processing_time_ms, request_ip, trace_id, created_at, updated_at)
VALUES
    ('CUST001234', 'HOME_LOAN',      5000000.00, 240, 780, 2400000.00, 10413878.40, 5413878.40, 'LOW_MEDIUM',  8.50, 43391.16, 'CALCULATED', 142, '192.168.1.1', 'abc123def456', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST001234', 'AUTO_LOAN',       800000.00, 60,  780, 2400000.00,  1001251.20,  201251.20, 'LOW_MEDIUM',  9.35, 16687.52, 'CALCULATED', 98,  '192.168.1.1', 'abc123def457', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST002345', 'PERSONAL_LOAN',   500000.00, 36,  720, 1500000.00,   607900.32,  107900.32, 'MEDIUM',     13.25, 16886.12, 'CALCULATED', 110, '10.0.0.5',   'bcd234efg567', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST003456', 'HOME_LOAN',      7500000.00, 300, 810, 3600000.00, 16181046.00, 8681046.00, 'LOW',        7.50, 53936.82, 'APPROVED',   156, '172.16.0.10', 'cde345fgh678', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST004567', 'BUSINESS_LOAN',  2000000.00, 84,  690, 1800000.00,  2858654.40,  858654.40, 'MEDIUM_HIGH',12.50, 34031.60, 'CALCULATED', 134, '10.0.0.6',  'def456ghi789', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST005678', 'EDUCATION_LOAN',  300000.00, 120, 710,  800000.00,   456018.00,  156018.00, 'MEDIUM',      9.00,  3800.15, 'CALCULATED', 89,  '192.168.1.2', 'efg567hij890', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST006789', 'PERSONAL_LOAN',   200000.00, 24,  640,  600000.00,   232749.36,   32749.36, 'HIGH',       15.00,  9697.89, 'REJECTED',  78,  '10.0.0.7',   'fgh678ijk901', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST007890', 'HOME_LOAN',      3000000.00, 180, 750, 1800000.00,  5393869.20, 2393869.20, 'LOW_MEDIUM',  8.75, 29965.94, 'CALCULATED', 167, '172.16.0.11', 'ghi789jkl012', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST008901', 'AUTO_LOAN',       600000.00, 48,  760, 1400000.00,   719548.80,  119548.80, 'LOW_MEDIUM',  9.10, 14990.60, 'APPROVED',   101, '192.168.1.3', 'hij890klm123', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CUST009012', 'BUSINESS_LOAN',  5000000.00, 60,  800, 4000000.00,  6522702.00, 1522702.00, 'LOW',       11.00, 108711.70, 'CALCULATED', 198, '10.0.0.8',  'ijk901lmn234', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO pricing_audit_logs
    (pricing_request_id, customer_id, action, description, performed_by, outcome,
     request_ip, trace_id, duration_ms, created_at)
VALUES
    (1, 'CUST001234', 'PRICING_CALCULATED', 'Interest rate 8.50% p.a. calculated for HOME_LOAN of ₹5000000',
     'system:pricing-engine', 'SUCCESS', '192.168.1.1', 'abc123def456', 142, CURRENT_TIMESTAMP),
    (2, 'CUST001234', 'PRICING_CALCULATED', 'Interest rate 9.35% p.a. calculated for AUTO_LOAN of ₹800000',
     'system:pricing-engine', 'SUCCESS', '192.168.1.1', 'abc123def457', 98, CURRENT_TIMESTAMP),
    (3, 'CUST002345', 'PRICING_CALCULATED', 'Interest rate 13.25% p.a. calculated for PERSONAL_LOAN of ₹500000',
     'system:pricing-engine', 'SUCCESS', '10.0.0.5', 'bcd234efg567', 110, CURRENT_TIMESTAMP),
    (7, 'CUST006789', 'PRICING_REJECTED', 'Credit score 640 below minimum required 650',
     'system:pricing-engine', 'REJECTED', '10.0.0.7', 'fgh678ijk901', 78, CURRENT_TIMESTAMP);
