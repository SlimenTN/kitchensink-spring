# kitchensink-spring

A migrated version of the JBoss EAP **kitchensink** quickstart:
https://github.com/jboss-developer/jboss-eap-quickstarts/tree/8.1.x/kitchensink

The original app (JSF page, JAX-RS REST API, JPA on a relational database, running on JBoss EAP) is rewritten
with Spring Boot and MongoDB. The migration follows the **strangler fig** pattern: both systems run side by side
behind a gateway, and traffic moves to the new system one feature at a time. During the migration the new system
behaves exactly like the legacy one (same URLs, same JSON, same page, same messages), which parity tests check.

## Versions

| | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.2 |
| Spring Cloud (gateway) | 2025.1.4 |
| MongoDB | 8.0 |
| Debezium (embedded) | 3.7.0.Final |
| Legacy database | MySQL 8 |

## What it serves

| Legacy feature | New system |
|---|---|
| `GET /rest/members/{id}` | `MemberRestController` |
| `GET /rest/members` | `MemberRestController` |
| `POST /rest/members` | `MemberRestController` |
| `index.jsf` page (member list + registration form) | `MemberController` + Thymeleaf copy of the page |

## Architecture changes

The legacy app used one JPA entity as REST model, business model and database model, with business rules spread
across the UI and the REST layer. The new system separates them:

- **`infrastructure/rest`, `infrastructure/web`**: REST API and members page (adapters, own request/response models).
- **`domaine`**: `MemberService` holds the rules (validation, unique email) and the domain models; it knows nothing
  about databases or the migration.
- **`infrastructure/database`**: `MemberPersistence` is the single entry point to the databases. It decides where
  each operation goes:
  - reads come from **MongoDB**;
  - registrations go to the **write store** chosen by `migration.write-store`: `legacy` (the legacy MySQL database,
    source of truth during the migration) or `new` (MongoDB, after cutover).
- **`infrastructure/legacydatabase`**: writes into (and reads back from) the legacy MySQL database, sharing its id
  sequence with the legacy app.
- **`infrastructure/cdc`**: keeps MongoDB in sync with MySQL (below).

## CDC: keeping MongoDB in sync

While the legacy database receives the writes, an embedded **Debezium** engine copies every change of the legacy
`Member` table into MongoDB:

- on the first start it copies all existing rows (snapshot), then follows the MySQL binlog;
- inserts and updates become upserts by `_id` (the legacy id), deletes become deletes; each Debezium batch is one
  bulk write;
- the binlog position is saved in `data/cdc`, so a restart resumes where it stopped; if that position is lost, the
  snapshot runs again by itself;
- no lock is taken on the MySQL server (it can be shared).

CDC goes straight to the persistence layer, not through the domain: it is a migration mechanism, removed after
cutover. It is switched on by `migration.cdc.enabled`.

## The gateway: running both systems

`kitchensink-gateway` (Spring Cloud Gateway, port **8090**) sits in front of both systems and keeps the legacy URLs
(`/kitchensink/...`). Each migrated route has a legacy weight and a new weight in its configuration:

- traffic is split per request for the REST API;
- the members page uses a sticky cookie (`KS_UI`), so showing the page and submitting the form go to the same system;
- setting a route's new weight to `0` sends everything back to legacy (rollback);
- each response carries an `X-Served-By` header and a log line says which system served it.

## Running it

### Containers

| Container | Purpose |
|---|---|
| MongoDB 8.0 (`kitchensink-mongo`, port 27017) | Database `kitchensink`, user `kitchensink_app`, collection `members` with the schema validator and the unique email index (`mongodb/init/01-members.js`) |
| MySQL 8 (port 3306) | The legacy database `kitchensink`: the legacy app user `kitchensink_app`, and a CDC user `kitchensink_cdc` with replication rights; binlog on in `ROW` format |

The legacy app (JBoss EAP, port 8080) must use the same MySQL database. The MongoDB `docker-compose.yml` and init
script live in the `mongodb/` folder next to this project, not in this repository.

### Environment variables

| Variable | Used for |
|---|---|
| `KITCHENSINK_MONGO_PASSWORD` | MongoDB user `kitchensink_app` |
| `KITCHENSINK_MYSQL_PASSWORD` | MySQL user `kitchensink_app` (writes to the legacy database) |
| `KITCHENSINK_CDC_PASSWORD` | MySQL user `kitchensink_cdc` (Debezium) |

### Start

```bash
# new system, port 8081
./mvnw spring-boot:run

# gateway, port 8090 (from the kitchensink-gateway project)
./mvnw spring-boot:run
```

Then open http://localhost:8090/kitchensink/index.jsf, or call
http://localhost:8090/kitchensink/rest/members.

### Tests

```bash
./mvnw verify
```

Unit tests and integration tests; the integration tests start their own MongoDB and MySQL with Testcontainers, so
Docker must be running. The parity tests comparing both systems live in the separate `kitchensink-parity-tests`
project.

## Cutover

1. Stop writes for a few seconds and let CDC copy the last legacy changes.
2. Set `migration.write-store=new` and deploy.
3. Route 100% of the traffic to the new system in the gateway.
4. Retire the legacy app, then remove the CDC and legacy database code.

After the switch, member ids continue from the biggest existing id, and emails stay unique ignoring case and
accents, like in MySQL.
