# Pricing Engine Feature

**Implemented In:** `PricingCalculator`, `PricingService`
**Status:** ✅ Fully Implemented

---

## Overview

The Pricing Engine is the core business feature that determines the **interest rate**, **EMI**, **eligibility**, and **risk classification** for a loan application. It combines pure mathematical calculation with policy-based adjustments specific to Bank's product portfolio.

---

## Business Context

Bank offers 5 loan products with differentiated pricing based on product risk:

| Product | Risk Profile | Base Rate Multiplier |
|---|---|---|
| HOME_LOAN | Secured, low risk | 1.0 (8.50%) |
| EDUCATION_LOAN | Social obligation | 1.1 (9.35%) |
| AUTO_LOAN | Depreciating asset | 1.2 (10.20%) |
| BUSINESS_LOAN | Business risk | 1.5 (12.75%) |
| PERSONAL_LOAN | Unsecured, high risk | 1.8 (15.30%) |

Additionally, an individual customer's creditworthiness (credit score) and loan size determine a personalised adjustment on top of the product rate.

---

## Pricing Formula

### Step 1: Eligibility Gate

Before any pricing:

| Check | Threshold | Exception |
|---|---|---|
| Minimum credit score | ≥ 650 | `InsufficientCreditScoreException` |
| Loan-to-income ratio | ≤ 60% of annual income | `LoanAmountExceedsEligibilityException` |

If either check fails, the request is rejected immediately — no rate is computed.

### Step 2: Base Rate × Product Multiplier

```
productRate = 8.50 × productMultiplier
```

| Product | productMultiplier | productRate |
|---|---|---|
| HOME_LOAN | 1.0 | 8.50% |
| EDUCATION_LOAN | 1.1 | 9.35% |
| AUTO_LOAN | 1.2 | 10.20% |
| BUSINESS_LOAN | 1.5 | 12.75% |
| PERSONAL_LOAN | 1.8 | 15.30% |

### Step 3: Credit Score Adjustment

```
finalRate = productRate + creditScoreAdjustment
```

| Credit Score | Adjustment | Rationale |
|---|---|---|
| ≥ 800 | −1.00% | Premium customer discount |
| ≥ 750 | −0.50% | Good credit discount |
| ≥ 700 | 0.00% | Standard rate |
| ≥ 650 | +0.50% | Elevated risk premium |
| < 650 | REJECTED | Not eligible |

### Step 4: Large Loan Discount

```
if loanAmount ≥ ₹50,00,000:
    finalRate = finalRate − 0.25
```

Bank incentivises large loans (₹50L+) with a loyalty discount.

### Step 5: Rate Floor

```
finalRate = max(finalRate, 6.50)
```

Minimum rate of 6.50% regardless of discounts — protects Bank's interest income.

---

## EMI Calculation

Uses the standard reducing-balance formula:

```
EMI = P × r × (1 + r)^n  /  ((1 + r)^n − 1)

Where:
  P = Principal (loan amount)
  r = Monthly rate = annual_rate / 12 / 100
  n = Tenure in months
```

**Example: ₹50,00,000 at 8.00% for 240 months (20 years):**
```
r = 8.00 / 12 / 100 = 0.006667
n = 240
EMI = 5000000 × 0.006667 × (1.006667)^240 / ((1.006667)^240 − 1)
    = ≈ 41,822
```

**Totals:**
```
Total Payable     = EMI × n = 41,822 × 240 = 10,037,280
Total Interest    = Total Payable − Principal = 10,037,280 − 5,000,000 = 5,037,280
```

---

## Full Worked Example

**Input:** HOME_LOAN, credit score 780, ₹60,00,000, 240 months, income ₹24,00,000

**Step 1 — Eligibility:**
- Credit score 780 ≥ 650 ✅
- Max loan = 24,00,000 × 0.60 = 14,40,000... wait: ₹60L > ₹14.4L → REJECTED?

Actually, loan-to-income applies to personal/unsecured products. For HOME_LOAN (secured), the check may be waived or use different thresholds. This distinction is a **known gap** in the current implementation — the eligibility check is applied uniformly for all products.

**Step 2 — Product Rate:** 8.50 × 1.0 = 8.50%

**Step 3 — Credit Score:** 780 ≥ 750 → −0.50% → 8.00%

**Step 4 — Large Loan:** ₹60L ≥ ₹50L → −0.25% → 7.75%

**Step 5 — Floor:** 7.75% > 6.50% → Final rate: **7.75%**

---

## Risk Classification

Based solely on credit score (post-eligibility):

| Risk Category | Score Range | Impact |
|---|---|---|
| `LOW` | ≥ 800 | Best rate, fastest approval |
| `LOW_MEDIUM` | 750–799 | −0.50% discount |
| `MEDIUM` | 700–749 | Standard rate |
| `MEDIUM_HIGH` | 650–699 | +0.50% premium |
| `HIGH` | < 650 | Rejected at eligibility |

---

## Known Gaps and Future Enhancements

| Gap | Status |
|---|---|
| Loan-to-income ratio not product-specific (secured vs unsecured) | Not implemented |
| Business loan eligibility uses business income, not salary | Not implemented |
| Dynamic base rate from configuration source (not hardcoded) | Partially — `epricing.pricing.base-rate` in `application.yml`, but not bound to `PricingCalculator` |
| Promotional rate override (campaign-based discount) | Not implemented |
| Joint applicant income aggregation | Not implemented |
| Rate lock / rate validity period | Not implemented |

---

## Cross-References

- [PricingCalculator.md](../utilities/PricingCalculator.md) — mathematical implementation
- [PricingService.md](../services/PricingService.md) — orchestration layer
- [PricingAPI.md](../api/PricingAPI.md) — HTTP interface
- [TestStrategy.md](../testing/TestStrategy.md) — `PricingCalculatorTest` details
