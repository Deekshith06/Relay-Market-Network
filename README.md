# Relay Market Network

Relay is a three-role marketplace and delivery system built with Java 17, JDBC,
PostgreSQL, and vanilla HTML/CSS/JavaScript. Customers shop database-backed
categories, receive server-priced delivery quotes, schedule gifts, and track the
assigned courier. Agents share location and execute deliveries; admins manage the
catalog, inventory, pricing, assignments, scheduled work, and live signals.

The Java process serves both `/api/*` and `frontend/`, so the normal deployment is
same-origin and needs no separate frontend server.

## Requirements

- Java 17+
- Maven 3.9+
- PostgreSQL 14+
- Node.js 20+ for frontend and HTTP integration checks

On Homebrew macOS, make the keg-only JDK visible first:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

## Database Setup

`schema.sql` is the complete development schema and seed script. It drops Relay
objects before recreating them, so do not run it against a database containing
data that must be retained.

```bash
createdb relay_delivery
psql -d relay_delivery -v ON_ERROR_STOP=1 -f schema.sql
```

For an existing Relay database, apply the additive migration instead. It keeps
users, carts, orders, and inventory intact while adding audit ledgers, optimistic
order versions, and the corrected cart identity sequence:

```bash
psql -d relay_delivery -v ON_ERROR_STOP=1 -f migrations/001_admin_audit_and_sequences.sql
psql -d relay_delivery -v ON_ERROR_STOP=1 -f migrations/002_delivery_verification.sql
```

It seeds 15 categories, 16 products, product variants, six vehicle types, pricing
tiers, coupons, delivery slots, zones, routes, addresses, agents, and sample
orders. The PostgreSQL-specific `CHECK`, partial index, `SKIP LOCKED`, and view
features are intentional.

## Run

```bash
export DB_URL=jdbc:postgresql://localhost:5432/relay_delivery
export DB_USER=postgres
export DB_PASSWORD=postgres
export PORT=8080
mvn clean package
java -jar target/relay-delivery-1.0.0.jar
```

Open `http://localhost:8080`.

| Role | Email | Password |
| --- | --- | --- |
| Customer | `maya@relay.demo` | `Demo@123` |
| Courier | `arjun@relay.demo` | `Demo@123` |
| Courier | `nila@relay.demo` | `Demo@123` |
| Admin | `admin@relay.demo` | `Demo@123` |

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/relay_delivery` | JDBC connection |
| `DB_USER` / `DB_PASSWORD` | `postgres` / `postgres` | Database credentials |
| `PORT` | `8080` | HTTP port |
| `FRONTEND_DIR` | `frontend` | Static asset directory |
| `SESSION_HOURS` | `24` | In-memory session lifetime |
| `QUOTE_MINUTES` | `10` | Secure quote lifetime |
| `SCHEDULE_POLL_SECONDS` | `30` | Due-order database polling interval |
| `TRACKING_RETENTION_DAYS` | `30` | Location-history retention |
| `CORS_ORIGIN` | empty | One exact allowed cross-origin client |
| `IMAGE_HOSTS` | `images.unsplash.com` | Allowed external product-image hosts |
| `DELIVERY_CODE_KEY` | generated local key file | Base64-encoded 32-byte production encryption key |
| `DELIVERY_CODE_KEY_FILE` | `.relay-delivery-code.key` | Development key file, created with owner-only permissions |
| `DELIVERY_CODE_VALID_DAYS` | `30` | Immediate-order verification-code lifetime |
| `DELIVERY_CODE_BLOCK_MINUTES` | `15` | Lockout after five failed verification attempts |

Do not commit database credentials or map-provider keys. The current frontend
ships the keyless `MapAdapter` fallback: it shows a route visual, coordinates,
timeline, distance, ETA, and external-map link when no SDK is configured.

## Working Flows

### Customer Journey
```mermaid
%%{init: {'theme': 'dark', 'themeVariables': { 'background': '#0f0f11', 'primaryColor': '#1e1e24', 'primaryTextColor': '#f5f5f7', 'lineColor': '#8e8e93' }}}%%
graph TD
    A[1. Browse Catalog] --> B[2. Configure Items & Cart]
    B --> C[3. Request Secure Quote]
    C --> D[4. Select Gift / Schedule]
    D --> E[5. Place Order & Lock Stock]
    E --> F[6. Track Courier & ETA]
    F --> G[7. Provide Delivery Code]

    classDef darkNode fill:#1e1e24,stroke:#3a3a3c,stroke-width:2px,color:#f5f5f7;
    class A,B,C,D,E,F,G darkNode;
```

### Courier (Agent) Operations Flow
```mermaid
%%{init: {'theme': 'dark', 'themeVariables': { 'background': '#0f0f11', 'primaryColor': '#1e1e24', 'primaryTextColor': '#f5f5f7', 'lineColor': '#8e8e93' }}}%%
graph TD
    H[1. Login & Share GPS] --> I[2. Wait for Auto-Assignment]
    I --> J[3. Pack & Dispatch Order]
    J --> K[4. Transit & Live Location]
    K --> L[5. Enter Customer OTP]
    L --> M[6. Deliver & Release Capacity]

    classDef darkNode fill:#1e1e24,stroke:#3a3a3c,stroke-width:2px,color:#f5f5f7;
    class H,I,J,K,L,M darkNode;
```

### Admin Operations Flow
```mermaid
%%{init: {'theme': 'dark', 'themeVariables': { 'background': '#0f0f11', 'primaryColor': '#1e1e24', 'primaryTextColor': '#f5f5f7', 'lineColor': '#8e8e93' }}}%%
graph TD
    N[1. Manage Catalog & Rules] --> O[2. Monitor Signals Dashboard]
    O --> P[3. Execute Manual Overrides]

    classDef darkNode fill:#1e1e24,stroke:#3a3a3c,stroke-width:2px,color:#f5f5f7;
    class N,O,P darkNode;
```



### 1. Customer Workflow
- **Catalog & Selection**: Customers browse categorized products, supporting both fixed-price items, variant configuration (e.g., color/size), and weight-based items.
- **Dynamic Pricing Engine**: Adding items to the cart requests a pricing quote. The system calculates base fares, progressive distance and weight fees, fragile handling surcharges, and discount coupons.
- **Gift Wrap & Scheduling**: Deliveries can be scheduled for future date/slots, or sent as gifts (which hides the price from packing slips and adds wrapping options).
- **Secure Order Placement**: Checking out locks the generated quote and decrements inventory in a transaction.
- **Real-Time Tracking**: Once dispatched, the customer can monitor the courier's live location, route map, timeline, and ETA.
- **Secure Verification**: On delivery, the customer provides their unique one-time verification code to the courier to complete the handoff.

### 2. Courier (Agent) Workflow
- **Shift Activation**: Couriers log in and enable location sharing. The system captures and evaluates speed, impossible coordinate jumps, and timestamp freshness.
- **Queue-Based Dispatch**: Orders are queued. The dispatcher finds the most optimal courier based on active carried weight, vehicle type, Haversine distance, rating, and current queue load.
- **Lifecycle Execution**: Couriers process orders through distinct stages: `ASSIGNED` -> `PICKED_UP` -> `OUT_FOR_DELIVERY` -> `DELIVERY_VERIFICATION`.
- **OTP Handoff**: The courier inputs the customer's 6-digit OTP code to finalize the order.

### 3. Admin Workflow
- **Operational Management**: Admins oversee products, update stock levels, manage coupon codes, and define granular pricing rules (e.g., platform charges, regional pricing zones).
- **Live Signal Dashboard**: Admins view a live control panel displaying agent positions, active delivery routes, scheduled gifts queue, and inventory alerts.
- **Audited Overrides**: Allows admins to manually assign/reassign couriers (protected by optimistic version locking) or bypass verification with a documented reason.

---

## Advanced Architecture & Data Flow

### 1. Double-Checkout Transactional Isolation
To prevent race conditions, stock double-booking, and quote tampering:
- During order creation, the database locks the pricing quote record using transaction isolation (`FOR UPDATE`).
- The engine reconstructs the shopping cart price, recalculates the dynamic rules, and compares it bit-for-bit with the submitted quote.
- Stock checks and decrementing occur inside the *same* database transaction block.
- An `Idempotency-Key` header ensures duplicate client requests safely return the original successful response instead of re-processing.

### 2. Core Scheduler & `SKIP LOCKED` Queue Polls
For scheduled orders and future gifts:
- A Java background scheduler wakes up periodically to poll pending scheduled tasks.
- It queries the database using PostgreSQL's `SELECT ... FOR UPDATE SKIP LOCKED`.
- This ensures multiple backend instances can query the queue simultaneously without blocking each other or double-dispatching the same scheduled delivery.

### 3. Agent Assignment Min-Heap & Dijkstra Router
- **Min-Heap Sorter**: The dispatch algorithm structures available agents into a Min-Heap. The priority is calculated using active order counts, remaining vehicle weight capacity, distance, and agent rating. Complexity is bounded at `O(a log a)`.
- **Dijkstra Route Optimizer**: Built on an adjacency-list graph representing valid transport routes. It handles shortest path reconstruction and filters out disconnected zones in `O((V + E) log V)` time.

### 4. Cryptographic Delivery OTP Integrity
- At startup, the system generates an owner-only, Base64-encoded 32-byte AES-GCM key file (`.relay-delivery-code.key`).
- Every order receives a 6-digit verification code.
- To prevent database leaks, the code is stored in the database as a salted PBKDF2 hash.
- For customer retrieval, the code is encrypted with AES-GCM and returned only to the authorized customer's profile.
- A cryptographic HMAC fingerprint prevents the code from being reused by future orders.

---

## Technology Stack

### Backend Architecture
- **Java 17 (JDK 17)**: Leverages modern Java features including Records, Switch Expressions, and Text Blocks.
- **Maven**: Build automation and dependency resolution.
- **PostgreSQL 14+**: Relational database handling transactions, partial indexes, check constraints, and concurrent queue processing.
- **JDBC & HikariCP**: Lightweight, raw SQL data mapping and high-performance database connection pooling.
- **PBKDF2 & AES-GCM**: Cryptographic engines for secure storage and encryption.

### Frontend Interface
- **Vanilla HTML5 & CSS3**: Pure CSS custom properties, grid layouts, keyframe transitions, and a fully responsive interface (supporting Mobile, Tablet, and Desktop form-factors) with keyboard focus trapping.
- **Vanilla ES6+ JavaScript**: Lightweight frontend logic utilizing the native Fetch API, standard DOM events, `AbortController` for debouncing, and Map adapters.

### Verification & Testing
- **Vitest**: Fast, Node.js-based test framework.
- **Integration Test Suite (`test:api`)**: Full security checkups, CORS restriction tests, login throttling verification, quote tampering simulations, and concurrency verification.

---

## Pricing And Orders

All currency uses Java `BigDecimal` and PostgreSQL `NUMERIC`. The pricing engine
loads active SQL rules and computes:

```text
products + base + progressive distance + progressive weight
+ category/fragile/temperature/insurance/priority charges
+ gift/schedule/platform charges + tax - discount = final payable
```

`POST /api/pricing/quote` persists a customer-owned snapshot under a random UUID.
At `POST /api/orders`, the backend locks the quote, rejects expiry/reuse, rebuilds
the catalog and rule calculation, compares every monetary component, locks stock,
uses conditional decrements, and marks the quote used in the same transaction.
An 8-100 character `Idempotency-Key` makes order retries return the original order.

Future gifts persist as `SCHEDULED`. A Core Java scheduler only triggers database
polls; `FOR UPDATE SKIP LOCKED` and conditional status updates release each due row
once after restarts. Slot rows serialize concurrent checkout capacity. Inventory
reservations are transactionally released on cancellation and committed when the
scheduled order is released for dispatch.

## API Surface

All non-auth endpoints require `Authorization: Bearer <token>` and enforce role or
resource ownership.

- Catalog/cart: `GET /api/categories`, `GET /api/products[/{id}]`, and cart CRUD at
  `/api/cart` and `/api/cart/items[/{id}]`.
- Checkout: `POST /api/pricing/quote`, `POST /api/orders`, order list/detail,
  lifecycle, route, and assignment endpoints.
- Addresses/slots: CRUD at `/api/addresses[/{id}]` and
  `GET /api/delivery/slots?date=YYYY-MM-DD&timezone=...`.
- Tracking: `POST /api/agents/me/location`, `DELETE /api/agents/me/location`, and
  `GET /api/orders/{id}/tracking`.
- Delivery verification: `POST /api/orders/{id}/verify-delivery` for the assigned
  courier and `POST /api/admin/orders/{id}/verify-delivery` with a required manual
  confirmation reason.
- Admin: overview, scheduled orders, live deliveries, pricing analytics, product,
  variant, stock, category, pricing-rule, assignment, and reassignment endpoints
  below `/api/admin`.

Manual assignment and reassignment require `expectedOrderVersion` from the order
being viewed. The database row is locked and its version is checked before agent
capacity, coverage, load, order status, status log, and assignment audit are
committed together. Stale admin actions return `409` instead of overwriting newer
dispatch work.

The shared frontend updates cart rows optimistically, serializes rapid quantity
changes, cancels obsolete quotes with `AbortController`, and polls only live order
resources. Product, category, stock, assignment, status, address, and pricing
mutations patch the affected panel without reloading the document.

Tracking validates coordinate ranges, timestamp freshness, assignment ownership,
duplicates, speed, and impossible jumps. Only the order customer, assigned agent,
or admin can read tracking; contact details are masked. Current location and
retained history are stored separately, and sharing is removed on completion,
cancellation, stop, or logout.

## Delivery Verification

Every committed order receives an unused six-digit code. The database stores a
salted PBKDF2 hash for comparison, AES-GCM ciphertext for customer-only display,
and a retained HMAC fingerprint that prevents reuse by future orders. Admin and
courier order/tracking responses omit the code. After delivery, the hash and
ciphertext are cleared while the fingerprint remains reserved.

The enforced lifecycle is `PLACED -> CONFIRMED -> PACKED -> ASSIGNED ->
PICKED_UP -> OUT_FOR_DELIVERY -> DELIVERY_VERIFICATION -> DELIVERED`.
`DELIVERED` is rejected by both normal status endpoints. The assigned courier or
an admin with a reason must submit the customer code; verification, delivery
timestamp, verifier identity, agent load release, status log, and security audit
commit atomically. Five failures create a temporary lockout, and expired, failed,
blocked, courier-verified, and admin-verified events are audited without recording
the submitted code.

## Dispatch Algorithms

- `AgentAssignmentService` builds a Min-Heap ordered by availability, remaining
  capacity fit, Haversine distance, active-order count, carried weight, rating,
  and deterministic agent ID. It is `O(a log a)` time and `O(a)` space.
- `RouteOptimizer` uses an adjacency-list graph and Dijkstra with route
  reconstruction. It rejects negative edges and reports disconnected zones in
  `O((V + E) log V)` time and `O(V + E)` space.
- `PendingOrderQueue` combines `ArrayDeque` and `HashSet` for FIFO `O(1)` average
  offer/poll/deduplication. Each dispatch pass scans the initial queue once, so an
  overweight head does not block later compatible work.
- Agent claims are conditional SQL updates inside the locked order transaction.
  Multiple application instances cannot exceed vehicle weight or active-order
  capacity.

## Verification

Run the repository checks:

```bash
mvn clean package
npm test
npm run lint
```

After loading a fresh schema and starting the JAR on port 8080 with
`CORS_ORIGIN=http://localhost:3000`, run:

```bash
npm run test:api
```

That HTTP suite covers security headers, restricted CORS, login throttling,
registration/re-login and logout invalidation, authorization, database
catalog/search, admin product lifecycle and stock audit, address and cart CRUD,
weight accumulation, fixed/variant/weight mixed pricing, quote tampering/reuse,
idempotency, inventory, price changes, tracking ownership and lifecycle, scheduled
gifts, capacity queuing, stale assignment rejection, customer-only delivery-code
exposure, direct-delivery rejection, courier/admin verification, one-time use,
five-attempt lockout, and audited reassignment.

Session tokens are intentionally process-local and expire after `SESSION_HOURS`.
Refreshing or navigating while the server is running preserves the session; after
a Java process restart, sign in again. Accounts and password hashes remain in
PostgreSQL and are unaffected by a restart.

## Manual End-To-End Check

1. Sign in as Maya; search/filter products, open a keyboard-accessible product
   modal, add fixed, variant, and weight items, and verify the fly-to-cart landing.
2. Deny browser location permission, enter an address manually, then allow it and
   confirm accuracy is shown. Change priority, gift, date, slot, or coupon and
   verify the debounced quote breakdown refreshes.
3. Confirm the final modal and double-click Place Order. Verify one order exists,
   then open it and inspect status, fallback map, timeline, distance, and ETA.
4. Place a future gift; verify recipient, occasion, hidden-price option, wrapping,
   schedule, and charges on customer, courier, and admin views.
5. Sign in as the assigned courier, share location, advance to Delivery
   Verification, and confirm unrelated users cannot track it. Enter Maya's code,
   complete delivery, and verify the code disappears and location sharing stops.
6. As admin, edit products, stock, variants, categories, and pricing rules; inspect
   scheduled orders, live/stale signals, analytics, low stock, assignment, and
   reassignment behavior.
7. Check desktop, tablet, and mobile widths, keyboard focus trapping, Escape close,
   restored modal focus, empty/loading/error/offline states, and reduced motion.
