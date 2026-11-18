# Jck_ActiveClientsNumber

**Enterprise Java 17 ETL synchronization agent built on Google Guice 7, Apache HttpClient 5, and Hibernate 6+ that extracts corporate member activity metrics from REST APIs, resolves dynamic reporting windows, and persists deduplicated records into MS SQL Server for SAP BW/4HANA.**

---

## 📋 Overview & Business Purpose

**Jck_ActiveClientsNumber** is an autonomous batch integration service responsible for collecting monthly and cumulative annual statistics of active corporate clients (`activeClients_Month`, `activeClients_Year`) segmented by corporate member identifiers (`memberCode`).

During each execution cycle, the agent dynamically calculates the exact start and end dates of the previous calendar month anchored to the **`Europe/Minsk`** timezone, appends `periodbegin` and `periodend` query parameters to the configured API gateway URL, sanitizes incoming JSON telemetry, deduplicates records by composite business keys (`YYYYMM` + `memberCode`), and commits transactional batches into the **`ActiveClientsNumber`** MS SQL staging table (PSA) for downstream ingestion into **SAP BW/4HANA**.

---

## 🚀 Core Architectural Highlights

* **Type-Safe Modular Dependency Injection:** Built on **Google Guice 7** (`ACNAppModule`), enforcing strict separation of concerns across configuration loading, HTTP transport, JSON deserialization, and Hibernate persistence while ensuring deterministic resource cleanup (`AutoCloseable` HTTP pools and `SessionFactory` teardown).
* **Timezone-Aware Reporting Window Resolution:** Automatically computes the first and last calendar days of the preceding month in `Europe/Minsk` (`LocalDate.now(ZoneId.of("Europe/Minsk")).minusMonths(1)`) and appends `periodbegin` and `periodend` parameters safely whether or not the base `data_endpoint` already contains query strings.
* **Pooled Apache HttpClient 5 Transport:** Encapsulates a thread-safe `PoolingHttpClientConnectionManager` inside `ACN_HttpClient` with explicit connection establishment (`connect_timeout_ms`) and socket read (`response_timeout_ms`) boundaries to prevent thread starvation under network latency.
* **Adaptive Exponential Backoff with Jitter:** Integrates a custom `ExponentialBackoffRetryStrategy` (extending `DefaultHttpRequestRetryStrategy`) to intercept transient I/O errors and retryable HTTP responses (`429`, `500`, `502`, `503`, `504`). Uses randomized jitter (`RANDOMIZATION_FACTOR = 0.5`), honors server `Retry-After` headers, and enforces a strict overall execution time budget (`max_timeout_ms`) starting from `initial_timeout_ms`.
* **State-Aware Lazy Client Initialization & TLS Hot-Swapping:** Defers `CloseableHttpClient` instantiation until the first outbound call. Before building the socket factory, `ACN_HttpClient::triggerCertUpdate` invokes `CertUpdater` for the target endpoint (and the OAuth2 identity provider when applicable), updating the local JVM `cacerts` truststore and hot-swapping the active `SSLContext` via `KeyStoreManager` with zero downtime.
* **Multi-Schema Authentication & OAuth2 Token Caching:** Dynamically resolves `Basic`, static `Bearer Token`, `OAuth2` (`client_credentials` grant with thread-safe in-memory token caching and a 30-second expiration safety buffer), and anonymous (`none`) schemes from `properties.yaml`.
* **Defensive JSON Deserialization & Composite-Key Deduplication:** Features a custom `ACN_DataDeserializer` that normalizes comma-separated decimals and scientific notation, rejects `NaN`/`Infinity` metrics, filters out malformed or overflowing `memberCode` values, and performs stream-based deduplication by `@Embeddable` composite key `ACN_PK` (`YYYYMM`, `memberCode`) prior to database persistence.
* **High-Throughput Hibernate Batching & Scheduler Observability:** Executes memory-bounded transactional batch merges (`flush`/`clear` every 50 entities) in `ACN_DAO` and writes machine-readable YAML execution summaries (`datetime`, `returned_code`, `records_processed`) to `exchange/result.yaml` alongside OS exit codes (`0` / `1`).
* **Resilient Hibernate Batching & Scheduler Observability:** Executes memory-bounded transactional batch merges (`flush`/`clear` every 50 entities) in `ACN_DAO` wrapped in an adaptive, jittered exponential backoff retry loop (`max_timeout_ms` budget) to survive transient MS SQL outages and deadlocks (`Error 1205`) without losing fetched payloads, while writing machine-readable YAML execution summaries (`datetime`, `returned_code`, `records_processed`) to `exchange/result.yaml`.

---

## 🏗️ Shared Dependency & Execution Topology

**ActiveClientsNumber** uses a decoupled shared-dependency topology to organize JARs and environment paths (such as `dependencies/`, `config/`, `exchange/`, and `logs/`) via centralized resolution and manifest configuration.

---

## 📂 Project Architecture & Component Breakdown

### ⚙️ Configuration, Security & Observability Utilities

> ⚠️ **Security Note:** Production credential files are git-ignored and must be configured from `.dist` templates.

* **`config/properties.yaml.dist`** — Template defining endpoint URLs, timeouts, and authentication mechanisms (Basic, static token, and OAuth2). For complete configuration structures and component breakdown, please refer to the project repository documentation.

### 🌐 Orchestration, Dependency Injection & Network Transport Layers

* **`acn.MainLauncher`** — Entry point and lifecycle coordinator managing `Log4j2`, `P6Spy`, Google Guice initialization, resource cleanup, `exchange/result.yaml` generation, and OS exit codes (`0` or `1`).
* **`acn.ACNAppModule`** — Guice `AbstractModule` binding singletons including `ACNApiConfig`, `ObjectMapper`, `HttpClientBuilder`, and Hibernate `SessionFactory`.
* **`acn.ACN_App`** — Pipeline orchestrator that computes calendar month boundaries in `Europe/Minsk`, fetches JSON via `ACN_HttpClient`, parses and deduplicates records, and invokes `ACN_DAO`.
* **`acn.network.ExponentialBackoffRetryStrategy`** — Custom retry mechanism for transient I/O errors and HTTP status codes (`429`, `500–504`) with randomized exponential backoff, `Retry-After` support, and a strict execution time budget (`max_timeout_ms`).
* **`acn.network.ACN_HttpClient`** — Thread-safe Apache HttpClient 5 wrapper supporting TLS certificate synchronization (`CertUpdater`, `KeyStoreManager`) and `basic`, `token`, `oauth2` (with in-memory token caching), or `none` authentication.

### 💾 Domain Model, Deserialization & Persistence Layer

* **`acn.jackson.ACN_DataDeserializer`** — Fault-tolerant Jackson deserializer parsing `YYYYMM` periods and `acliveClients` data while handling comma-separated numbers, scientific notation, `NaN`/`Infinity` rejection, `memberCode` overflow guards, and malformed elements.
* **`acn.model.ACN_Data` & `acn.model.ACN`** — Root and granular DTOs mapping JSON payloads and corporate member telemetry.
* **`acn.model.ACN_PK`** — `@Embeddable` composite primary key (`YYYYMM` and `memberCode`) with `equals()` and `hashCode()` for deduplication.
* **`acn.model.ACN_Entity`** — JPA entity mapped to the `ActiveClientsNumber` staging table.
* **`acn.model.ExecutionResult`** — DTO modeling YAML execution summaries.
* **`acn.hibernate.ACN_DAO`** — Transactional DAO managing batch `session.merge()` operations (`flush`/`clear` every 50 records) protected by an exponential backoff retry policy with randomized jitter and a strict `max_timeout_ms` budget. Automatically classifies SQL/Hibernate exceptions to retry transient connection drops, timeouts, and MS SQL deadlocks (`Error 1205`) while failing fast on permanent constraint violations.


### 📊 Generated Runtime Artifacts

* **`logs/`** — Runtime directory containing `commons.log` and SQL telemetry (`p6spy.log`).
* **`exchange/result.yaml`** — Completion summary file for enterprise schedulers (SAP CPS, Cron, etc.):

```yaml
datetime: "2026-11-18 15:30:00"
returned_code: 0
records_processed: 1420
```
---

## 🧪 Testing Strategy & Verification Scenarios

The project utilizes an automated testing suite powered by **JUnit 5 (Jupiter)** and **Mockito** to ensure stability across deserialization, network security, and pipeline integration.

### 1. Test Suite Coverage

* **Deserialization & Sanitization (`ACN_DataDeserializerTest`):** Verifies date parsing, DTO mapping, and metric normalization.
* **Network & Security Layer (`ACN_HttpClientTest`):** Tests authentication handling, TLS lifecycle sync, and error handling.
* **Pipeline Integration & Period Calculation (`ACN_AppTest`):** Validates calendar month calculations and deduplication logic.
* **Database Resilience & Retry Layer (`ACN_DAOTest`):** Verifies periodic batch flushing (`50` entities), exponential backoff recovery on `JDBCConnectionException` and MS SQL deadlocks (`LockAcquisitionException` / `Error 1205`), immediate fail-fast behavior on `ConstraintViolationException`, and retry termination when `max_timeout_ms` is exhausted.

### 2. Running the Test Suite

Execute the test suite using Maven:

```bash
mvn clean test
```

---

## 🛠️ Prerequisites

* **Java:** JDK 17 or higher *(mandated by **Jakarta Persistence (JPA 3.1+)** namespace compatibility required by **Hibernate 6+**, **Google Guice 7** dependency injection runtime, modern **Switch Expressions** used in authentication routing, and **TLS 1.3** cryptographic provider support)*
* **Database:** Microsoft SQL Server (target staging table: `ActiveClientsNumber`)
* **Build Tool:** Apache Maven 3.8+

---

## 💻 Build, Deployment & Execution

### 1. Prepare Environment Configurations

Copy configuration templates from the `config/` directory:
* `config/properties.yaml.dist` → `config/properties.yaml`
* `config/hibernate.cfg.xml.dist` → `config/Hibernate.cfg.xml`

### 2. Build the Application

Run the standard Maven `package` phase to compile the application, isolate shared dependencies, and copy the executable artifact into the project root directory:

```bash
mvn clean package
```

During the `package` lifecycle, Maven automatically performs the following steps:

1. Compiles the Java source files under JDK 17 specifications.
2. Triggers `maven-dependency-plugin` to copy all required third-party runtime libraries into the shared `../dependencies/` folder.
3. Packages `Jck_ActiveClientsNumber.jar` with a manifest `Class-Path` pointing to `../dependencies/` and automatically copies the compiled JAR from `target/` directly into the **application root directory** (`${project.basedir}`) via `maven-resources-plugin`.

### 3. Run the Application

Launch the application from the root directory using the Java CLI. Because `Jck_ActiveClientsNumber.jar` resides directly in the application root alongside `config/`, `ConfigPathResolver` and the manifest `Class-Path` resolve all configuration files and `../dependencies/` out of the box:

```bash
# 1. Execute using the default local logs directory (./logs/)
java -jar /path/to/Jck_ActiveClientsNumber/Jck_ActiveClientsNumber.jar

# 2. Alternative: Execute with an explicit log directory override via JVM System Properties
java -Dlog.dir=/var/log/acn_applications -jar /path/to/Jck_ActiveClientsNumber/Jck_ActiveClientsNumber.jar
```


