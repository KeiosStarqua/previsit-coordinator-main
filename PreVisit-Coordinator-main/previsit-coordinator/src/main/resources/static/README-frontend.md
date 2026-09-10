# Clinic dashboard (frontend) — 3D hybrid UI

Single-page dashboard for the PreVisit Call-E backend, covering all six screens,
behind a **login screen**.

> Login uses a session cookie, so use the **served** option below
> (http://localhost:8080/). Opening `index.html` as a `file://` page cannot keep
> a login session. Accounts live in the backend's SQLite database; each user's
> case list is stored in the browser's localStorage, namespaced by username.

## How to run it (two options)

### Recommended: let the backend serve it (no CORS)
1. `cd previsit-coordinator && mvn spring-boot:run`
2. Open **http://localhost:8080/** — Spring Boot serves this page from
   `src/main/resources/static/`, so API calls are same-origin and just work.

### Standalone: open the file directly
You can also double-click `index.html` (it opens as `file://...`). In that mode
the page defaults its API base to **http://localhost:8080** and shows a banner.
For the browser to allow those cross-origin calls, the backend must send CORS
headers — the included `WebCorsConfig` class does this for `/api/**` in dev.
Start the backend first, then open the file.

> ⚠️ If you opened the file **without** the backend running (or on an older
> build without `WebCorsConfig`), you'll see errors like
> `fetch ... file:///C:/api/health blocked by CORS`. That means the page had no
> backend to reach. Start the backend and reload, or use the recommended option.

Use the **⚙** button in the top bar to point the dashboard at any backend URL
(e.g. a different host/port); leave it blank to use the page's own origin.

## Endpoints used
- POST/GET `/api/intake-cases`, `/intake-submission`, `/scheduling-approval`
- POST/GET `/call-runs`, GET `/available-slots`, POST `/slot-proposal`, `/appointment-confirmation`
- GET `/api/staff-alerts`, POST `/api/staff-alerts/{id}/resolution`, GET `/api/health`

## 3D interface
- Animated WebGL backdrop (Three.js): floating glass/wireframe polyhedra,
  particle dust, pointer parallax, theme-aware colors.
- Frosted-glass panels with perspective, depth shadows, tilt-on-hover, float-in.
- Three.js loads from cdnjs; without internet the backdrop is skipped and the
  dashboard still works. Honors `prefers-reduced-motion`.

## Notes
The backend responses don't echo intake details or a case list, so the page
keeps a local mirror (browser localStorage) of what staff enter; case *status*
is always re-fetched from the backend. It enforces the same workflow order the
service enforces: intake → (non-emergency) approve → load slots → propose →
verbal confirm → appointment. Emergency-flagged cases are halted and raise a
staff alert.

## New backend file
`src/main/java/com/previsitcoordinator/WebCorsConfig.java` — dev CORS for
`/api/**` (allows all origins; no credentials). Tighten before real deployment.
