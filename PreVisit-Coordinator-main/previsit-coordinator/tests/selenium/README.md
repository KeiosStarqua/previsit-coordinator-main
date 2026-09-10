# Selenium end-to-end test — PreVisit Call-E dashboard

Drives a **real, visible Chrome window** against the running dashboard and
exercises every feature on screen, using the patient reference **Dang** and the
phone number **+6588044731**.

## What it covers
0. Sign in with the demo account (registers it on the first run)
1. Backend health indicator shows "online"
2. Start an intake case (Dang / +6588044731)
3. Record the intake submission (non-emergency)
4. Place a Call-E coordination call and read the result
5. Poll the call result again ("Check result")
6. Approve scheduling
7. Load available appointment slots
8. Propose a slot
9. Give verbal confirmation and confirm the appointment
10. Staff-review handoff reports "ready for clinic staff"
11. Toggle the light/dark theme
12. Emergency path — a second flagged case is halted
13. A staff alert is raised for the halted case, then resolved

Each step prints `[PASS]`/`[FAIL]` and saves a screenshot under
`tests/selenium/screenshots/`. The process exits non-zero if anything fails.

## Prerequisites
- The dashboard must be running first (in another terminal):
  ```powershell
  cd previsit-coordinator
  .\mvnw.cmd spring-boot:run
  ```
  and reachable at http://localhost:8080/
- Python 3.9+ and Google Chrome installed.
- Selenium 4.15+ (ships "Selenium Manager", which downloads the matching
  chromedriver automatically — no manual driver setup).

## Install & run (Windows PowerShell)
```powershell
cd previsit-coordinator\tests\selenium
pip install -r requirements.txt
python test_previsit_dashboard.py
```

## Optional environment overrides
| Variable      | Default                 | Purpose                              |
|---------------|-------------------------|--------------------------------------|
| `BASE_URL`    | `http://localhost:8080` | Where the dashboard is served        |
| `PATIENT_REF` | `Dang`                  | Patient reference used in the test   |
| `PHONE`       | `+6588044731`           | Demo phone number (E.164)            |
| `USERNAME`    | `dang`                  | Login username (registered if new)   |
| `PASSWORD`    | `previsit123`           | Login password                       |
| `HEADLESS`    | `0` (visible)           | Set `1` to run without a window      |

Example — run headless against another host:
```powershell
$env:HEADLESS="1"; $env:BASE_URL="http://localhost:9090"; python test_previsit_dashboard.py
```

## Notes
- The test clears the browser's local case list at the start so its assertions
  are deterministic; it does not touch the backend's other state.
- The backend keeps cases in memory, so each restart of the server begins fresh.
- Assertions are scoped to individual panels where a word (e.g. "Appointment
  scheduled") also appears as a permanent timeline label, so a green run means
  the appointment truly persisted — not just that the label is on screen.
