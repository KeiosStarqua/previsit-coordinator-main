# PreVisit Call-E Coordinator

A healthcare **pre-visit coordination** demo for the CALL-E hackathon. Clinic
staff start an intake case for a demo patient, a coordination call collects
non-diagnostic pre-visit information, and an appointment is scheduled **only
after explicit verbal confirmation** of the exact slot. Emergency-flagged cases
are halted and raised as staff alerts.

This is a scheduling and coordination workflow — **not** a medical diagnosis
system. It never diagnoses, never replaces emergency services, and never
schedules a case flagged as a possible emergency. The demo uses fake patients or
consenting teammates only.

---

## Stack

| Layer     | Technology                                                        |
|-----------|-------------------------------------------------------------------|
| Backend   | Java 25, Spring Boot 4 (Spring Web MVC + Validation + JDBC), Maven|
| Database  | SQLite (`previsit.db`) via `spring-boot-starter-jdbc` + `sqlite-jdbc` |
| Auth      | Session login; PBKDF2-hashed passwords (JDK only, no Spring Security) |
| Frontend  | Single-page dashboard (HTML/CSS/JS) with a Three.js 3D backdrop   |
| Scheduling| Mock appointment gateway (pluggable `AppointmentGateway`)         |
| Calls     | Mock CALL-E adapter (pluggable `CallEAdapter`)                    |
| Tests     | JUnit (backend) · Selenium end-to-end (dashboard)                 |

The frontend is served by the backend from `src/main/resources/static/`, so the
whole app runs on one origin at `http://localhost:8080/`.

---

## Project layout

```
previsit-coordinator/
├─ pom.xml
├─ mvnw, mvnw.cmd, .mvn/            # Maven Wrapper (no global Maven needed)
├─ src/main/java/com/previsitcoordinator/
│  ├─ PrevisitCoordinatorApplication.java
│  ├─ HealthController.java
│  ├─ WebCorsConfig.java            # dev CORS for /api/**
│  └─ intake/                       # case lifecycle, CALL-E + appointment boundaries
├─ src/main/resources/static/
│  ├─ index.html                    # the clinic dashboard (3D hybrid UI)
│  └─ README-frontend.md
├─ src/test/java/...                # backend tests
└─ tests/selenium/                  # Selenium end-to-end test
   ├─ test_previsit_dashboard.py
   ├─ requirements.txt
   └─ README.md
doc/                                # project purpose & build brief
CONTEXT.md                          # domain language / ubiquitous terms
```

---

## Prerequisites

- **JDK 25** (the project targets Java 25). Verify with `java -version`.
- Maven is optional — the included wrapper (`mvnw` / `mvnw.cmd`) downloads it on
  first run.
- For the Selenium test: **Python 3.9+** and **Google Chrome**.

---

## Run the app

### Windows (PowerShell)
```powershell
cd previsit-coordinator
.\mvnw.cmd spring-boot:run
```

### macOS / Linux
```bash
cd previsit-coordinator
./mvnw spring-boot:run
```

Then open **http://localhost:8080/**. You'll be asked to **sign in or create an
account** first — the dashboard is gated behind login. Coordination cases are
held in memory (fresh each restart); **user accounts and activity are persisted
in SQLite** (`previsit.db`, created next to where you start the app).

> Login uses a session cookie, so open the app at the address the backend serves
> (`http://localhost:8080/`). Opening `index.html` as a file can't hold a login
> session.

### Accounts & the database
- **Create account** with your name, a username (3+ chars) and a password (6+ chars).
- Passwords are stored only as salted **PBKDF2** hashes — never in plain text.
- `previsit.db` contains three tables (see `src/main/resources/schema.sql`):
  `users`, `login_audit` (login/logout/register events), and `case_audit`
  (one row per intake case a user starts).
- Inspect it with any SQLite tool, e.g. `sqlite3 previsit.db "SELECT username, display_name, created_at, last_login_at FROM users;"`

---

## API

Base path `/api`. All payloads are JSON.

All `/api/**` routes require a signed-in session **except** `/api/health` and
`/api/auth/**`.

| Method & path                                             | Purpose                                         |
|-----------------------------------------------------------|-------------------------------------------------|
| `POST /api/auth/register`                                 | Create an account `{username, displayName, password}` and sign in |
| `POST /api/auth/login`                                    | Sign in `{username, password}`                  |
| `POST /api/auth/logout`                                   | End the session                                 |
| `GET  /api/auth/me`                                       | Current user `{username, displayName, caseCount}` (401 if not signed in) |
| `GET  /api/health`                                        | Liveness check → `{"status":"ok"}`              |
| `POST /api/intake-cases`                                  | Start a case `{patientReference, demoPhoneNumber}` |
| `GET  /api/intake-cases/{id}`                             | Fetch a case                                    |
| `POST /api/intake-cases/{id}/intake-submission`           | Record intake (sets emergency flag)             |
| `POST /api/intake-cases/{id}/scheduling-approval`         | Approve a non-emergency case                    |
| `POST /api/intake-cases/{id}/call-runs`                   | Start a CALL-E coordination call                |
| `GET  /api/intake-cases/{id}/call-runs/{runId}`           | Poll a call run                                 |
| `GET  /api/intake-cases/{id}/available-slots`             | List offered slots                              |
| `POST /api/intake-cases/{id}/slot-proposal`               | Propose one slot (intent only)                  |
| `POST /api/intake-cases/{id}/appointment-confirmation`    | Book after explicit `confirmed: true`           |
| `GET  /api/staff-alerts`                                  | List active staff alerts                        |
| `POST /api/staff-alerts/{alertId}/resolution`             | Resolve an alert (non-diagnostic note)          |

**Demo phone numbers** must be E.164 (`+` then 8–15 digits, e.g. `+6588044731`).

---

## Case lifecycle

```
STAFF_STARTED ──intake (non-emergency)──▶ INTAKE_COMPLETE ──approve──▶ SCHEDULING_APPROVED
      │                                                                      │
      └──intake (emergency flag)──▶ SCHEDULING_HALTED (+ staff alert)        └─ propose → confirm → appointment (SCHEDULED)
```

---

## Testing

### Backend unit tests
```bash
cd previsit-coordinator
./mvnw test          # or .\mvnw.cmd test on Windows
```

### Selenium end-to-end (drives the live dashboard)
Start the app first, then in another terminal:
```bash
cd previsit-coordinator/tests/selenium
pip install -r requirements.txt
python test_previsit_dashboard.py
```
It walks all 13 feature flows on screen (start case → intake → call → approve →
propose → confirm → staff review, plus the emergency-halt and alert-resolution
paths) and saves screenshots. See `tests/selenium/README.md` for options.

---

## Safety boundaries

- Never diagnoses; stores only fixed, non-diagnostic call outcomes (never a transcript).
- Never continues scheduling after a possible emergency red flag.
- Never creates an appointment without explicit confirmation of the exact slot.
- Fake patients or consenting teammates only.

---

## License

Add a license of your choice (e.g. MIT) before publishing if this will be public.
