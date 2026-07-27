# actbrow

Embeddable AI assistant for web apps: a Spring Boot backend + SDK that lets a coding agent
scan your repo and push tools, navigation flows, and knowledge live, plus a Next.js dashboard
to manage assistants and a drop-in browser widget.

**ActBrow is an [in-app AI agent runtime](https://actbrow.depak.dev)** — [embed an AI agent that navigates your app](https://actbrow.depak.dev/docs) with two script tags, no backend rewrite.
See the [React example](https://actbrow.depak.dev/examples/react), [Vue example](https://actbrow.depak.dev/examples/vue), or [self-hosting guide](https://actbrow.depak.dev/self-hosting) · [Book a demo](https://actbrow.depak.dev/book-a-demo).

- **Backend** (this repo) — Spring Boot (Java 21), PostgreSQL, OpenAI-compatible model provider (OpenRouter, or a local Claude-CLI proxy for dev). Serves `actbrow-sdk.js` + `actbrow-widget.js`.
- **UI** — Next.js dashboard & landing page in a [separate repo](https://github.com/depak7/actBrow-ui). Proxies `/api/*` to this backend.

## Quickstart (Docker)

Clone both repos so the UI sits at `./ui` next to this backend (required by `docker-compose.yml`):

```bash
git clone https://github.com/depak7/actBrow.git
cd actBrow
git clone https://github.com/depak7/actBrow-ui.git ui

cp .env.example .env            # optional: fill in model/OAuth/mail values
docker compose up --build
```

- UI → http://localhost:3000
- API → http://localhost:8080  (health: `/health`)
- Postgres → localhost:5432

This brings up Postgres, the backend, and the UI together. Google login and the model provider
need real credentials (see Configuration); the app boots without them for local exploration.

API-only (no dashboard):

```bash
docker compose up --build postgres actbrow
```

## Run locally (without Docker)

### Backend (this repo)

```bash
# needs a running Postgres matching SPRING_DATASOURCE_* (see .env.example)
./mvnw spring-boot:run
```

### UI ([actBrow-ui](https://github.com/depak7/actBrow-ui))

```bash
git clone https://github.com/depak7/actBrow-ui.git
cd actBrow-ui
cp .env.example .env.local      # point NEXT_PUBLIC_API_PROXY_TARGET at your backend
npm install
npm run dev                     # http://localhost:3000
```

## Configuration

Backend env vars (see [`.env.example`](.env.example)); the committed
`src/main/resources/application.properties` reads them via `${VAR:default}`:

| Variable | Purpose |
|----------|---------|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection |
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_CHAT_MODEL` | Model provider (OpenRouter or compatible) |
| `GOOGLE_OAUTH_CLIENT_ID` | Google Sign-In (login) |
| `ACTBROW_CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins |
| `SIGNUP_NOTIFY_ENABLED` / `SIGNUP_NOTIFY_RECIPIENT` / `MAIL_*` | Signup & waitlist email notifications (SMTP) |

UI env vars: see [`.env.example` in actBrow-ui](https://github.com/depak7/actBrow-ui/blob/master/.env.example). `NEXT_PUBLIC_*` values are inlined into the browser bundle — never put secrets there.

## Tests

```bash
./mvnw test          # backend
```

UI type check lives in [actBrow-ui](https://github.com/depak7/actBrow-ui): `npx tsc --noEmit`.

## License

[MIT](LICENSE)
