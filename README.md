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

Agent harness knobs (optional; defaults are safe):

| Variable / property | Purpose | Default |
|---------------------|---------|---------|
| `actbrow.agent.max-steps` | Max plan/execute loops per run | `15` |
| `actbrow.agent.tool-timeout` | Client / pending tool wait | `10s` |
| `actbrow.agent.max-tool-retries` | Failures per tool before exhaustion | `2` |
| `actbrow.agent.parallel-tool-calls` | Fan out pure server/API tool batches | `true` |
| `actbrow.agent.max-parallel-tools` | Concurrent API tools per step | `8` |
| `actbrow.agent.max-active-tool-schemas` | Tools exposed in the model schema | `25` |

## Architecture

ActBrow is **Agent = model + harness**. Spring AI (OpenAI-compatible client) talks to the LLM. The **product harness** owns the loop, tools, state, specialists, verification, and the browser widget protocol. Reliability comes from deterministic runtime policy, not only prompt wording.

### System map

```text
┌─────────────────────────────────────────────────────────────────────────┐
│  Host web app                                                           │
│  actbrow-sdk.js  +  actbrow-widget.js                                   │
│    · PAGE_CONTEXT snapshot on each user turn                            │
│    · SSE: assistant deltas, tool.call.requested / completed             │
│    · Executes CLIENT tools (navigate, observe, browser HTTP)            │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ HTTP + SSE
┌───────────────────────────────▼─────────────────────────────────────────┐
│  Spring Boot API  (this repo)                                           │
│  /v1/conversations · /v1/runs · /v1/assistants · tools · knowledge · MCP│
│                                                                         │
│  RunService  ── plan / execute / verify loop                            │
│    ├─ RunPlanner + ContextAssembler + HarnessPromptContract             │
│    ├─ ProgressiveToolDisclosureService  (specialists + search/activate) │
│    ├─ OpenAiCompatibleModelProvider     (Spring AI ChatModel)           │
│    ├─ RunExecutor  (HTTP / MCP / knowledge / client park)               │
│    ├─ FailureClassifier → RunVerifier → RunPolicyEngine                 │
│    └─ RunMemory · checkpoints · eval traces · circuits · feature flags  │
│                                                                         │
│  PostgreSQL  (conversations, runs, tools, knowledge, Flyway)            │
└─────────────────────────────────────────────────────────────────────────┘
        │
        ▼
  OpenAI-compatible model (OpenRouter, Gemini, vLLM, …)
```

**UI dashboard** ([actBrow-ui](https://github.com/depak7/actBrow-ui)) manages assistants, tools, runs, and themes; it is not on the hot path of every widget message.

### Request lifecycle (one user turn)

1. **Widget** posts a turn with user text + optional `PAGE_CONTEXT` (path, elements, visible text).
2. **Run** is created (`PENDING` → `IN_PROGRESS`) and processed on a virtual thread.
3. Each **step** (up to `max-steps`):
   - Load catalog for the assistant; select tools for the **active specialist**.
   - Build system prompt + **turn contract** + specialist guidance + working memory.
   - Model returns either a **final answer** or **tool call(s)**.
   - Tools execute (see below); results append as `TOOL` messages paired by `toolCallId`.
   - **Verifier + policy** decide: continue, switch tool, force final answer, or need user.
4. SSE streams deltas and tool events; run completes, fails, or cancels with CAS status transitions.

### Agent harness (core loop)

| Layer | Role | Main types |
|-------|------|------------|
| **Plan** | Context + model decision | `RunPlanner`, `ContextAssembler`, `OpenAiCompatibleModelProvider` |
| **Tools** | Catalog, schema, domain | `ToolService`, `ToolCatalogPolicies`, `ToolDomain` |
| **Disclose** | Limit schemas; specialists | `ProgressiveToolDisclosureService` |
| **Execute** | Server vs client tools | `RunExecutor`, `PendingClientToolStore`, HTTP/MCP/knowledge executors |
| **Recover** | Retries & unknown tools | `RunToolFailureTracker`, synthetic unknown-tool results |
| **Verify** | Classify outcome | `FailureClassifier`, `RunVerifier` |
| **Policy** | Deterministic next action | `RunPolicyEngine` (continue / switch / stop / user) |
| **State** | Continuity | `RunMemoryService`, checkpoints, conversation messages |
| **Observe** | Ops & evals | `EvalTraceRecorder`, audit log, run inspection API |
| **Prompt** | Bounded efficiency | `HarnessPromptContract` (scope · evidence · done-when · stop) |

Prompt rules request behavior; **code enforces** max steps, duplicate-call blocks, circuit breakers, specialist isolation, and terminal policies.

### Dual specialists (BROWSER / API)

One run, **one active specialist** at a time so the model only sees relevant tools (reduces invented operator-tool names).

| Specialist | Tools | Switch |
|------------|-------|--------|
| **BROWSER** (default) | `app.navigate`, `page.observe`, `page.screenshot`, `path.find`, client tools | `agent.use_browser` |
| **API** | Operator `SERVER_HTTP`, MCP | `agent.use_api` |
| **Shared** | `knowledge.search`, `tool.search`, `tool.activate`, agent switches | always |

**Harness auto-route** (preferred over manual switch):

- `tool.search` hits are pure API (or pure browser) → switch specialist **and** activate keys  
- `tool.activate` for a pure domain → switch then activate  
- Direct call of a real catalog tool in the other domain → `routeForToolCall` then execute  

Mixed browser+API activates do **not** blind-switch.

### Tool execution model

```text
Model emits N tool calls in one step
        │
        ▼
  Resolve names (wire form app_navigate ↔ app.navigate; catalog soft-match)
        │
        ├─ Unknown / invented  → synthetic failure + available tools list (run continues)
        │
        ├─ All executable calls parallel-safe (server HTTP / MCP / knowledge, not meta)
        │     → Java virtual threads, capped by max-parallel-tools
        │
        └─ Otherwise sequential
              CLIENT / browser HTTP park run → WAITING_FOR_CLIENT_TOOL
              Widget completes tool → TOOL message → loop resumes
```

- **Parallel-safe:** pure server-side APIs (and similar).  
- **Never parallel with others:** meta tools (`tool.search`, activate, agent switches) — they mutate specialist state.  
- **Never parallel among themselves for run status:** client navigate/observe (single client park).  
- **Navigate:** at most one successful navigation per user turn; extras deferred with guidance.

Built-in client tools are seeded from `BuiltinToolCatalog`. Operator tools attach via API/OpenAPI import/MCP sync.

### Package layout (backend)

```text
com.actbrow.actbrow
├── api/           REST controllers + DTOs
├── agent/         ModelProvider, tool call records, conversation window
├── service/       Run loop, tools, memory, policy, executors (the harness)
├── model/         JPA entities (Run, Conversation, Tool, …)
├── repository/    Spring Data
├── conversation/  PAGE_CONTEXT parse, user message display
└── config/        Properties, CORS, auth filter, cache
```

Static assets: `src/main/resources/static/actbrow-sdk.js`, `actbrow-widget.js`.  
Schema: Flyway under `src/main/resources/db/migration/`.

### Data & consistency notes

- **Runs** use CAS status transitions so cancel/delete cannot be overwritten by a late writer.  
- **Conversation** stores ASSISTANT `[tool_calls]…` envelopes and TOOL rows with `toolCallId` for valid multi-turn model history.  
- **Run memory** holds objective, last action, failures, active tool keys, and `specialistAgent`.  
- **Tool circuits** are keyed per `assistantId|toolKey` so one tenant cannot trip another.

### Design principles

1. **Harness > model upgrade** for product failures (wrong tool, thrash, invented names).  
2. **Evidence first** — PAGE_CONTEXT and successful tool results beat chat speculation.  
3. **Bounded turns** — scope, done-when, stop; no multi-plan thrash for its own sake.  
4. **Recoverable tools** — failures name what failed, what is safe, and a next valid action.  
5. **Thin model adapter** — Spring AI for chat/tools; product loop stays first-party Java.

## Tests

```bash
./mvnw test          # backend
```

Harness-focused suites include unknown-tool recovery, specialist auto-route, parallel safety, prompt contract, run-loop query count, verifier/policy, and tool search ranking.

UI type check lives in [actBrow-ui](https://github.com/depak7/actBrow-ui): `npx tsc --noEmit`.

## License

[MIT](LICENSE)
