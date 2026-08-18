# Revenue Intelligence — Data & Integration Contracts v1.0

Status: implementation baseline  
Core baseline: `core-v1.1.0-project-baseline` (`199411e`)  
Owner: Product Engineering

## 1. Boundary

`revenue-intelligence` is a code-first business module. It owns customer identity, sales orders, advertising spend, touchpoints and attribution results. Core continues to own identity/access, tenant context, audit, outbox, file storage and navigation. The module never writes directly to another module's domain tables.

## 2. Canonical entities

| Entity | Natural idempotency key | Required source fields | Sensitive data policy |
|---|---|---|---|
| Customer | `(source_system, external_id)` | `source_system`, `external_id` | normalized email/phone are SHA-256 hashed; only masked display values are retained |
| Sales order | `(source_system, external_id)` | customer reference, ordered time, monetary fields | no payment credential or free-form PII |
| Ad spend | `(source_system, external_id)` | spend date, channel, amount, currency | non-PII |
| Touchpoint | `(source_system, external_id)` | customer reference, event time, channel | no raw IP, cookie or device identifier in MVP |
| Attribution result | `(order_id, model)` | computed from canonical orders/touchpoints | derived data |

All tables include `tenant_id`; PostgreSQL RLS is enabled and forced. Runtime queries are still required to bind tenant explicitly as defence in depth.

## 3. CSV contracts

Uploads are UTF-8 CSV with a header row. Header names are case-insensitive and trimmed. Quoted values and escaped quotes are supported. A file checksum makes each dataset import idempotent for one tenant.

### customers

`source_system,external_id,full_name,email,phone,history_complete`

### orders

`source_system,external_id,customer_source,customer_external_id,ordered_at,gross_amount,discount_amount,returned_amount,cancelled_amount,shipping_amount,tax_amount,source_channel,business_model,status`

- ISO-8601 timestamps are mandatory.
- Currency amounts use decimal dot and must be non-negative.
- `business_model`: `WHOLESALE`, `RETAIL`, or blank for rule-based derivation.
- `status`: `COMPLETED`, `RETURNED`, `PARTIALLY_RETURNED`, `CANCELLED`.

### ad-spend

`source_system,external_id,spend_date,channel,campaign,amount,currency`

### touchpoints

`source_system,external_id,customer_source,customer_external_id,occurred_at,channel,campaign,source_medium,event_type`

Rows failing validation are written to `import_error`; valid rows continue. The response reports accepted/rejected counts and the persisted batch id.

## 4. REST API v1

Base path: `/api/v1/revenue-intelligence`.

| Method/path | Permission | Contract |
|---|---|---|
| `POST /imports/{dataset}` | `REVENUE_IMPORT/CREATE` | multipart field `file`; dataset is `customers`, `orders`, `ad-spend`, `touchpoints` |
| `GET /imports` | `REVENUE_IMPORT/READ` | last 50 import batches |
| `GET /imports/{id}/errors` | `REVENUE_IMPORT/READ` | rejected rows for a batch |
| `GET /reconciliation?from=&to=` | `REVENUE_ANALYTICS/READ` | gross, reductions, net revenue and reconciliation gap |
| `GET /customers` | `REVENUE_CUSTOMER/READ` | masked customer identity and lifecycle |
| `POST /attribution/rebuild?from=&to=` | `REVENUE_ATTRIBUTION/UPDATE` | rebuild first-touch and last-non-direct results |
| `GET /dashboard?from=&to=` | `REVENUE_ANALYTICS/READ` | KPI and channel/lifecycle/business-model breakdowns |

Error responses use `application/problem+json` through the Core exception handler. Successful imports publish `revenue-import.completed.v1`; attribution rebuild publishes `revenue-attribution.rebuilt.v1` in the same transaction.

## 5. Calculation contracts

- `net_revenue = gross_amount - discount_amount - returned_amount - cancelled_amount`.
- Shipping and tax are reported separately and excluded from net revenue.
- A customer is `RETURNING` only when a previous valid order exists. If imported history is incomplete and no prior order exists, lifecycle is `UNKNOWN`, not `NEW`.
- First-touch chooses the earliest touchpoint within 30 days before the order.
- Last-non-direct chooses the latest non-direct touchpoint in that window; if absent, it falls back to the order's supplied source channel, then `DIRECT_UNKNOWN`.
- `ROAS = attributed paid-channel revenue / advertising spend`; `MER = total net revenue / advertising spend`; division by zero returns null.

## 6. Operational gates

- A dashboard period with order-to-customer match coverage below 95% must display a data-quality warning.
- Reconciliation variance must be zero against the module's canonical order formula before acceptance; reconciliation against an external accounting ledger remains pending until that source is connected.
- Production ingestion is blocked until the Data Discovery checklist identifies system owners, timezone, currency, history completeness, deletion/retention policy and sample extracts.
