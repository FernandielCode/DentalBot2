# DentalBot2

Spring Boot backend for a dental clinic WhatsApp bot integrated with the **Meta WhatsApp Cloud API**.

The bot serves two audiences:

- **Patients**: schedule an appointment through a guided WhatsApp conversation.
- **The doctor/admin**: search patients, update patients/appointments, register patients, review appointments, and run the existing payment workflow through WhatsApp interactive messages.

## Contents

- [Overview & Domain Context](#overview--domain-context)
- [Tech Stack & Key Dependencies](#tech-stack--key-dependencies)
- [Project Structure](#project-structure)
- [Local Setup & Configuration](#local-setup--configuration)
- [Webhook & API Flow](#webhook--api-flow)
- [Testing & Troubleshooting](#testing--troubleshooting)
- [Architectural Decision Records (ADRs)](#architectural-decision-records-adrs)
- [Contributing](#contributing)
- [Known Unknowns / `[VERIFY IN CODE/ENV]`](#known-unknowns--verify-in-codeenv)

---

## Overview & Domain Context

### What the application does

`DentalBot2` exposes a Meta WhatsApp webhook and a small REST API for clinic data.

- Incoming WhatsApp messages are received at `POST /api/whatsapp/webhook`.
- The webhook is verified by Meta at `GET /api/whatsapp/webhook`.
- `WhatsAppBotService` decides whether the sender is the configured doctor or a patient.
- Patients follow a persisted, multi-message intake flow that creates a `Patient` and a pending `Appointment`.
- The doctor gets a WhatsApp menu with search, update, charge, patient-list, and patient-registration actions.
- Outbound replies are sent through `WhatsAppClient` to the Graph API endpoint `/{phone-number-id}/messages`.

### What it does **not** currently use

- No WhatsApp Flows DTOs or flow JSON resources are present in the current codebase.
- No WebSocket/messaging dependency is used.
- No template-message sending is implemented (`TemplateParameters` is an empty record).
- No webhook signature validation (`X-Hub-Signature-256`) is implemented.
- No Flyway/Liquibase migration tool is present.

### High-level request flow

```text
Meta WhatsApp Cloud API
        │  POST /api/whatsapp/webhook
        ▼
WhatsAppWebhookController
        ▼
WhatsAppBotService.processWebhookEvent(...)
        │
        ├─ persist WhatsAppContact + WhatsAppMessage
        ├─ sender == DOCTOR_WHATSAPP_PHONE ?
        │       └─ handleDoctorMessage(...)  → interactive menus / flows
        └─ otherwise
                └─ patient state machine (WhatsAppSession.currentState)
                        └─ create/update Patient + Appointment
        ▼
WhatsAppClient
        └─ POST https://graph.facebook.com/.../{phoneNumberId}/messages
```

### WhatsApp integration surface

| Surface | Implemented in | Notes |
|---|---|---|
| Webhook verification (`GET`) | `WhatsAppWebhookController.verifyWebhook` | Checks `hub.mode=subscribe` and `hub.verify_token == whatsapp.verify-token` |
| Incoming messages (`POST`) | `WhatsAppWebhookController.receiveWebhookEvent` | Deserializes `WhatsAppWebhookPayload`; always returns `200 EVENT_RECEIVED` |
| Text replies | `SendMessageRequest` + `WhatsAppClient.sendTextMessage` | Basic text message |
| Interactive lists | `SendInteractiveListRequest` + `WhatsAppClient.sendInteractiveList` | Up to 10 rows; used for menus and field selection |
| Interactive reply buttons | `SendInteractiveButtonRequest` + `WhatsAppClient.sendInteractiveButtons` | Up to 3 buttons; used for `PACIENTE`/`CITA`, `SI`/`NO`, patient state |
| WhatsApp Flow messages | Not present | The codebase currently uses lists/buttons, not Flows |
| Template messages | Not implemented | `TemplateParameters` is an empty placeholder |

> `[VERIFY IN CODE/ENV]` The exact Meta app configuration (app ID, app secret, webhook callback URL, subscribed fields, production phone number) is not stored in this repository.

---

## Tech Stack & Key Dependencies

### Backend

| Item | Value | Source |
|---|---|---|
| Java | 21 | `pom.xml` |
| Spring Boot | 4.1.1 | `pom.xml` parent |
| Web | `spring-boot-starter-webmvc` | `pom.xml` |
| Security | `spring-boot-starter-security` | `pom.xml` |
| Persistence | `spring-boot-starter-data-jpa`, `spring-boot-starter-data-jdbc` | `pom.xml` |
| Database | PostgreSQL (`org.postgresql:postgresql`) | `pom.xml` |
| Templating | `spring-boot-starter-thymeleaf` | Declared; no Java source references were found |
| PDF | `openhtmltopdf-pdfbox`, `openhtmltopdf-slf4j` | Declared; no Java source references were found |
| Dev tooling | `spring-boot-devtools`, `spring-boot-docker-compose` | `pom.xml` |
| Build | Maven Wrapper (`mvnw`, `mvnw.cmd`) | Repository root |
| Tests | Spring Boot test starters + JUnit 5 | `pom.xml` |

### Frontend / auxiliary assets

| Asset | Location | Notes |
|---|---|---|
| Vue 3 + Vite app | `src/main/resources/vue-project1/` | Pinia, Vue Router, Vitest, ESLint, Oxlint, Prettier; excluded from the Maven resource jar |
| Static clinic site | `staticWebSite/` | Plain HTML/images; outside `src/main/resources`, not packaged by Maven |

The Spring Boot artifact is the deployable backend. The Vue app and static site are separate assets and must be built/served separately.

---

## Project Structure

```text
.
├── pom.xml
├── compose.yaml                 # PostgreSQL service definition
├── Dockerfile                   # Two-stage Java 21 build/run image
├── mvnw / mvnw.cmd
├── staticWebSite/               # Static clinic marketing site (outside resources)
├── src/
│   ├── main/
│   │   ├── java/com/example/Project1/
│   │   │   ├── Project1Application.java        # Spring Boot entry point
│   │   │   ├── Config/
│   │   │   │   ├── SecurityConfig.java         # Permissive dev security + CORS
│   │   │   │   └── WhatsAppProperties.java     # whatsapp.* configuration binding
│   │   │   ├── Controller/
│   │   │   │   ├── WhatsAppWebhookController.java
│   │   │   │   ├── PatientController.java
│   │   │   │   ├── AppointmentController.java
│   │   │   │   └── DentistController.java      # Empty placeholder
│   │   │   ├── Model/
│   │   │   │   ├── DTO/                        # Meta request/response records
│   │   │   │   └── Entity/                     # JPA entities + enums
│   │   │   ├── repository/                     # Spring Data JPA repositories
│   │   │   └── Service/
│   │   │       ├── WhatsAppBotService.java     # Main bot/state machine
│   │   │       ├── WhatsAppClient.java         # Graph API client
│   │   │       └── PatientService.java         # Patient CRUD service
│   │   └── resources/
│   │       ├── application.properties
│   │       └── vue-project1/                   # Independent Vue app
│   └── test/java/com/example/Project1/
│       └── Project1ApplicationTests.java       # contextLoads() only
```

---

## Local Setup & Configuration

### Prerequisites

- **Java 21** (the Dockerfile uses `eclipse-temurin:21`).
- Maven Wrapper (included).
- **Docker** (optional but recommended for PostgreSQL).
- **Node.js `^22.18.0 || >=24.12.0`** for the Vue app (`package.json` engines).
- A Meta WhatsApp Cloud API app with:
  - A WhatsApp phone number ID.
  - A permanent/temporary access token.
  - A webhook verify token.
- A public HTTPS URL for webhook delivery. The CORS config anticipates `ngrok` domains (`https://*.ngrok-free.app`, `https://*.ngrok.io`).

### Required environment variables (backend)

These are referenced by `src/main/resources/application.properties` or `compose.yaml`:

| Variable | Used by | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `application.properties` | JDBC URL for PostgreSQL |
| `WHATSAPP_PHONE_NUMBER_ID` | `application.properties` / `WhatsAppProperties` | Meta phone number ID |
| `WHATSAPP_ACCESS_TOKEN` | `application.properties` / `WhatsAppProperties` | Graph API bearer token |
| `WHATSAPP_VERIFY_TOKEN` | `application.properties` / `WhatsAppProperties` | Webhook verification token |
| `DOCTOR_WHATSAPP_PHONE` | `application.properties` | Doctor's WhatsApp number (`whatsapp.doctor.phone-number`) |
| `DATABASE_DB` | `compose.yaml` | PostgreSQL database name |
| `PRODUCTION_USERNAME_DB` | `compose.yaml` | PostgreSQL user |
| `PRODUCTION_PASSWORD_DB` | `compose.yaml` | PostgreSQL password |

**Do not commit real tokens or passwords.**

### Variables observed in `.env` that are not referenced by current code

`.env` is gitignored. It currently contains keys such as:

- `USERNAME_DB`
- `PASSWORD_DB`
- `PORT_DB`
- `WHATSAPP_BUSINESS_PHONE_NUMBER_ID`
- `WHATSAPP_FLOW_PATIENT_REGISTRATION`
- `WHATSAPP_FLOW_UPDATE_APPOINTMENT`
- `WHATSAPP_FLOW_SEARCH_PATIENT`

These are **not referenced** by the current `application.properties` or Java sources. No WhatsApp Flow DTO or `flows/` resource directory exists in the current tree.

> `[VERIFY IN CODE/ENV]` Confirm which of these variables are still intended for local use.

### Critical configuration binding caveat

`application.properties` currently uses:

```properties
whatsapp.api.url=...
whatsapp.api.phone-number-id=...
whatsapp.api.access-token=...
whatsapp.api.verify-token=...
```

But `WhatsAppProperties` declares:

```java
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    private String apiUrl;
    private String phoneNumberId;
    private String accessToken;
    private String verifyToken;
    ...
}
```

Spring Boot relaxed binding maps `whatsapp.api-url` to `apiUrl`, **not** `whatsapp.api.url`. The current nested keys may not bind to these fields, leaving the class defaults (`${WHATSAPP_PHONE_NUMBER_ID}`, etc.) as literal strings.

> `[VERIFY IN CODE/ENV]` Reconcile these property names before relying on outbound Meta calls. Either change `application.properties` to `whatsapp.api-url`, `whatsapp.phone-number-id`, `whatsapp.access-token`, `whatsapp.verify-token`, or refactor `WhatsAppProperties` to a nested `Api` object.

Also note: `application.properties` sets the Graph API base to `https://graph.facebook.com/v21.0`, while `WhatsAppProperties` defaults to `https://graph.facebook.com/v26.0`.

> `[VERIFY IN CODE/ENV]` Confirm which API version is intended.

### Database

`compose.yaml` starts PostgreSQL and maps the container port `5432` to host port `5433`:

```yaml
services:
  postgres:
    image: 'postgres:latest'
    container_name: posgres-DentalBot
    environment:
      - 'POSTGRES_DB=${DATABASE_DB}'
      - 'POSTGRES_USER=${PRODUCTION_USERNAME_DB}'
      - 'POSTGRES_PASSWORD=${PRODUCTION_PASSWORD_DB}'
    ports:
      - '5433:5432'
```

`application.properties` uses:

```properties
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
```

> **Warning:** `ddl-auto=create-drop` recreates the schema and **drops data on restart**. This is a development setting, not a production setting.

### Run locally (PowerShell / Windows)

```powershell
# 1. Start PostgreSQL (optional if you already have a database)
docker compose up -d

# 2. Set the required environment variables
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/<database>"
$env:WHATSAPP_PHONE_NUMBER_ID = "<meta-phone-number-id>"
$env:WHATSAPP_ACCESS_TOKEN  = "<meta-access-token>"
$env:WHATSAPP_VERIFY_TOKEN  = "<webhook-verify-token>"
$env:DOCTOR_WHATSAPP_PHONE  = "<doctor-whatsapp-number>"

# 3. Run the backend
.\mvnw.cmd spring-boot:run
```

Spring Boot does **not** read `.env` automatically. Export variables in your shell or configure them in your IDE/run configuration.

### Run locally (Unix-like shell)

```bash
docker compose up -d

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/<database>"
export WHATSAPP_PHONE_NUMBER_ID="<meta-phone-number-id>"
export WHATSAPP_ACCESS_TOKEN="<meta-access-token>"
export WHATSAPP_VERIFY_TOKEN="<webhook-verify-token>"
export DOCTOR_WHATSAPP_PHONE="<doctor-whatsapp-number>"

./mvnw spring-boot:run
```

The app starts on Spring Boot's default port **8080** (no `server.port` override is present).

### Docker

```bash
docker build -t dentalbot .
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://host.docker.internal:5433/<database>" \
  -e WHATSAPP_PHONE_NUMBER_ID="<meta-phone-number-id>" \
  -e WHATSAPP_ACCESS_TOKEN="<meta-access-token>" \
  -e WHATSAPP_VERIFY_TOKEN="<webhook-verify-token>" \
  -e DOCTOR_WHATSAPP_PHONE="<doctor-whatsapp-number>" \
  dentalbot
```

The Dockerfile builds with `./mvnw clean package -DskipTests`, runs as a non-root `spring` user, and exposes `8080`.

### Meta webhook setup

1. Expose the local app publicly, for example:
   ```bash
   ngrok http 8080
   ```
2. In the Meta app dashboard, set the callback URL to:
   ```text
   https://<public-host>/api/whatsapp/webhook
   ```
3. Set the verify token to the same value as `WHATSAPP_VERIFY_TOKEN`.
4. Subscribe to the `messages` webhook field (the code reads `change.value.messages`).
5. Send a WhatsApp message to the business number and confirm the webhook logs.

> `[VERIFY IN CODE/ENV]` The exact Meta app/webhook configuration and production hostname are external to this repository.

### Vue frontend (separate)

```bash
cd src/main/resources/vue-project1
npm install
npm run dev
```

Useful scripts from `package.json`:

- `npm run build`
- `npm run type-check`
- `npm run lint`
- `npm run test:unit`

> `[VERIFY IN CODE/ENV]` The Vue app's API base URL and deployment target are not defined in the inspected backend configuration.

### Tests and build

```bash
# Compile
./mvnw -DskipTests compile

# Run tests
./mvnw test

# Package (same command used by the Dockerfile)
./mvnw clean package -DskipTests
```

On Windows, substitute `.\mvnw.cmd`.

Current backend test coverage is minimal: the only test is `Project1ApplicationTests.contextLoads()`. Because it uses `@SpringBootTest`, it boots the full application context and needs the datasource configuration to be resolvable.

---

## Webhook & API Flow

### Webhook endpoints

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/api/whatsapp/webhook` | Meta verification. Requires `hub.mode=subscribe` and a matching `hub.verify_token`; returns `hub.challenge` or `403`. |
| `POST` | `/api/whatsapp/webhook` | Receives Meta events. Calls `WhatsAppBotService.processWebhookEvent`, logs exceptions, and always returns `200 EVENT_RECEIVED`. |

### Incoming payload handling

`WhatsAppWebhookPayload` is a record tree with `entry → changes → value → messages`.

`WhatsAppWebhookPayload.IncomingMessage` exposes:

- `getExtractedText()`: returns `text.body`, or the title of an interactive list/button reply.
- `getInteractiveSelectionId()`: returns the selected list/button ID.

The bot processes each message as follows:

1. Creates or updates a `WhatsAppContact` by `wa_id`.
2. Saves the incoming message in `whatsapp_messages`.
3. If `from` equals `whatsapp.doctor.phone-number`, routes to `handleDoctorMessage`.
4. Otherwise, runs the patient intake state machine.

### Patient intake state machine

Persisted in `WhatsAppSession.currentState` (`whatsapp_sessions` table):

| State | Behavior |
|---|---|
| `INITIAL` / `COMPLETED` | Sends the welcome prompt asking for full name and age; sets `AWAITING_PATIENT_INFO`. |
| `AWAITING_PATIENT_INFO` | Parses name/age, creates or updates a `Patient`, creates a `PENDING` `Appointment`, notifies the doctor, sets `COMPLETED`. |

The suggested appointment uses the next business day at **10:00 AM**, skipping Sundays.

### Doctor commands and flows

The doctor is identified by exact match against `DOCTOR_WHATSAPP_PHONE`.

| Trigger | Behavior |
|---|---|
| `hola`, `menu`, `opciones` | Sends the main interactive menu: `Buscar`, `Cambiar`, `Cobrar`, `Pacientes`, `Registrar paciente`. |
| `citas` | Paginated appointment list (10 per page) with a `Ver más citas` button. |
| `pago` or menu `Cobrar` | Sends the treatment list, then the legacy payment-method list. |
| Menu `Buscar` | Asks for a search query; searches name, lastname, full name, phone, date, and treatment. |
| Menu `Cambiar` | Sends `PACIENTE` / `CITA` buttons, then the search flow, then the update field flow. |
| Menu `Pacientes` | Paginated patient list (10 per page) with a `Ver más pacientes` button. |
| Menu `Registrar paciente` | Sequential patient registration (name, lastname, phone, age, patient state, payment state). |
| Date confirmation | When `isWaitingDate` is true, parses a doctor-supplied date and confirms the latest `PENDING` appointment. |

### Doctor conversation states (in-memory)

`WhatsAppBotService` keeps a `ConcurrentHashMap<String, DoctorSession>` keyed by doctor phone. The states are:

- `IDLE`
- `AWAITING_SEARCH_QUERY`
- `UPDATE_AWAITING_TARGET`
- `UPDATE_AWAITING_PATIENT_SELECTION`
- `UPDATE_AWAITING_APPOINTMENT_SELECTION`
- `UPDATE_AWAITING_FIELD_SELECTION`
- `UPDATE_AWAITING_NEW_VALUE`
- `UPDATE_AWAITING_CONFIRM`
- `REGISTER_AWAITING_FIELD`

This doctor state is **not persisted**. It is lost on application restart and is not shared across instances.

### Update flow details

- Search matches name, lastname, full name, phone, appointment date, and treatment.
- One match shows full patient details + appointments.
- Multiple matches show a text list of all matches.
- No match: `El paciente que busca no está registrado`.
- Update target is either `PATIENT` or `CITA`.
- If multiple patients match, the doctor selects one from an interactive list.
- If target is `CITA` and the patient has multiple appointments, the doctor selects one.
- Field lists:
  - `PATIENT`: name, lastname, phone, age, patient state, payment state.
  - `CITA`: appointment date/time, dentist, treatment, category, appointment status, notes, appointment date/time (detail), payment method.
- Changes are accumulated in `DoctorSession.pendingChanges` and are only saved when the doctor taps `SI`.
- `NO` returns to the field list; pending changes remain in the session.
- `patientAppointment`/`appointmentDateTime` update both `startAppointmentAt` and `appointmentDateTime`.

### Patient registration flow details

- A `Patient` is created immediately with `patientState = UNDEFINED`.
- The bot asks, in order:
  1. Name
  2. Lastname
  3. Phone
  4. Age
  5. Patient state (`POR DEFINIR`, `EN SEGUIMIENTO`, `CERRADO` as interactive buttons)
  6. Payment state (`COMPLETO`, `PARCIALMENTE`, `EN DEUDA`, `POR DEFINIR` as an interactive list)
- Each field is saved immediately after it is received.
- The payment state uses a list because Meta quick-reply buttons allow at most 3 options.

### Internal REST API

| Method | Path | Controller | Behavior |
|---|---|---|---|
| `GET` | `/api/patients` | `PatientController` | List all patients |
| `GET` | `/api/patients/{id}` | `PatientController` | Get a patient by UUID |
| `POST` | `/api/patients` | `PatientController` | Save a patient |
| `GET` | `/api/appointments` | `AppointmentController` | List all appointments |
| `GET` | `/api/appointments/{id}` | `AppointmentController` | Get an appointment by UUID |
| `PATCH` | `/api/appointments/{id}/status` | `AppointmentController` | Update `status` and/or `notes` |
| `GET`/`POST` | `/api/whatsapp/webhook` | `WhatsAppWebhookController` | Verification / event receiver |

`DentistController` exists but is empty.

### Database tables (from `@Table` annotations)

| Table | Entity | Active in current bot logic? |
|---|---|---|
| `patients` | `Patient` | Yes |
| `appointments` | `Appointment` | Yes |
| `dentists` | `Dentist` | Yes (active dentist lookup, update validation) |
| `treatments` | `Treatments` | Yes (list/price lookup, update validation) |
| `appointment_treatments` | Join table | Yes |
| `whatsapp_contacts` | `WhatsAppContact` | Yes |
| `whatsapp_sessions` | `WhatsAppSession` | Yes (patient `currentState`) |
| `whatsapp_messages` | `WhatsAppMessage` | Yes (message audit) |
| `message_threads` | `MessageThread` | Modeled; no bot/controller references found |
| `messages` | `Message` | Modeled; no bot/controller references found |
| `bot_commands` | `BotCommand` | Modeled; no bot/controller references found |
| `notifications` | `Notification` | Modeled; no bot/controller references found |
| `whatsapp_templates` | `WhatsAppTemplate` | Modeled; no bot/controller references found |
| `whatsapp_webhook_events` | `WhatsAppWebhookEvent` | Modeled; no bot/controller references found |

### Domain enums

| Enum | Values |
|---|---|
| `PatientState` | `UNDEFINED` (`POR DEFINIR`), `ON_TRACKING` (`EN SEGUIMIENTO`), `CLOSED` (`CERRADO`) |
| `PatientPaymentState` | `COMPLETE` (`COMPLETO`), `PARTIALLY` (`PARCIALMENTE`), `IN_DEBT` (`EN DEUDA`), `UNDEFINED_PRICE` (`POR DEFINIR`) |
| `PaymentMethod` | `CASH` (`EFECTIVO`), `TRANSFER` (`TRANSFERENCIA`) |

---

## Testing & Troubleshooting

### Running tests

```bash
./mvnw test
```

- Only `Project1ApplicationTests.contextLoads()` is present.
- It starts the full Spring context, so the datasource configuration must be resolvable.
- There are no bot-flow integration tests yet.

### Common pitfalls

| Symptom | Likely cause | What to check |
|---|---|---|
| Webhook verification returns `403` | Token mismatch | `WHATSAPP_VERIFY_TOKEN` must equal the Meta dashboard verify token |
| Outbound Meta calls fail or use placeholder values | `whatsapp.api.*` keys do not bind to `WhatsAppProperties` | Reconcile property names; see the configuration caveat above |
| Outbound Meta calls fail with an auth/permission error | Meta access token expired or invalid | Refresh `WHATSAPP_ACCESS_TOKEN` and restart the app |
| Data disappears after restart | `spring.jpa.hibernate.ddl-auto=create-drop` | Use migrations and a non-destructive setting for production |
| Doctor commands do not trigger | `DOCTOR_WHATSAPP_PHONE` does not exactly match the incoming `from` value | Confirm the international number format used by Meta |
| Doctor multi-step flow resets | Doctor session state is in-memory | Restart clears `DoctorSession`; do not scale horizontally without moving state to a shared store |
| Pending date confirmation is lost | `isWaitingDate` is a single in-memory boolean | Restart or concurrent doctor messages can reset/overwrite it |
| Duplicate replies for one Meta event | Controller always returns `200`; no idempotency check | Implement `WhatsAppWebhookEvent.eventKey` deduplication |
| Webhook spoofing risk | No `X-Hub-Signature-256` validation | Add app-secret signature verification before production |
| REST API is publicly readable/writable | `SecurityConfig` permits all requests | Add authentication/authorization before production |
| `Cobrar` flow behaves unexpectedly | Legacy PAGO interaction has no dedicated state machine | Review `handleLegacyTreatmentSelection` before relying on it |
| Treatment list fails to map | Native query returns `SendInteractiveListRequest.Row` | `[VERIFY IN CODE/ENV]` the native projection against the PostgreSQL schema |
| Vue app not served by Spring Boot | `vue-project1/**` is excluded from Maven resources | Run the Vue dev server separately |
| `ngrok` URL changed | Meta still points to the old host | Update the callback URL in Meta and restart the tunnel |

### Security notes

- `SecurityConfig` disables CSRF and permits **all** requests.
- CORS allows `http://localhost:*`, `https://*.ngrok-free.app`, and `https://*.ngrok.io`.
- `PatientController` and `AppointmentController` add local dev `@CrossOrigin` origins (`localhost:5173`, `:3000`, `:5157`).
- There is **no webhook signature verification** and **no REST API authentication**.
- These choices are suitable for local development only. Do not deploy as-is.

---

## Architectural Decision Records (ADRs)

> No formal `docs/` or `adr/` directory exists in this repository. The records below are **inferred from the codebase** and describe the patterns that future contributors and AI assistants must preserve unless they intentionally replace them.

### ADR-0001: Layered Spring Boot Monolith

- **Status:** Inferred from Codebase
- **Context:** The application combines a Meta webhook, a conversational bot, and a small clinic REST API in one deployable unit.
- **Decision:** Use a single Spring Boot application with package boundaries: `Controller` → `Service` → `repository`, plus `Model/Entity`, `Model/DTO`, and `Config`.
- **Consequences:**
  - Webhook handling belongs in `WhatsAppWebhookController`; orchestration belongs in `WhatsAppBotService`.
  - Controllers should not call Meta directly; use `WhatsAppClient`.
  - DTOs represent external Meta payloads; entities represent persistence.
  - Keep domain logic out of controllers.

### ADR-0002: Webhook-First Meta Integration with `RestClient`

- **Status:** Inferred from Codebase
- **Context:** Meta pushes events to the application and expects HTTP responses; outbound replies go to the Graph API.
- **Decision:** Implement `GET` verification and `POST` event reception in `WhatsAppWebhookController`; use `WhatsAppClient` with Spring `RestClient` for outbound messages.
- **Consequences:**
  - A publicly reachable HTTPS endpoint is required.
  - Verify-token handling is mandatory for Meta registration.
  - Outbound credentials and phone number ID must be valid.
  - The current code does not validate webhook signatures; do not assume the request is authentic without adding that check.

### ADR-0003: Hybrid Conversation State

- **Status:** Inferred from Codebase
- **Context:** Both patient intake and doctor administrative workflows span multiple messages.
- **Decision:**
  - Patient state is persisted in `WhatsAppSession.currentState` (`INITIAL`, `AWAITING_PATIENT_INFO`, `COMPLETED`).
  - Doctor multi-step state is kept in an in-memory `ConcurrentHashMap<String, DoctorSession>` keyed by doctor phone.
- **Consequences:**
  - Patient state survives application restarts; doctor state does not.
  - Doctor flows are not safe for multiple application instances without moving state to a shared store.
  - Preserve state transitions and reset doctor state on the global `hola` escape hatch.

### ADR-0004: Doctor Identity by Configured Phone Number

- **Status:** Inferred from Codebase
- **Context:** The clinic has a single doctor/admin in the current model.
- **Decision:** Compare the incoming `from` value to `whatsapp.doctor.phone-number` (`DOCTOR_WHATSAPP_PHONE`). Matching senders get administrative commands; everyone else is treated as a patient.
- **Consequences:**
  - The doctor phone must match Meta's `from` format exactly.
  - There is no user/role table or authentication layer.
  - Adding multiple doctors or staff requires refactoring identity and permissions.

### ADR-0005: Interactive Lists and Buttons Instead of Flows

- **Status:** Inferred from Codebase
- **Context:** The doctor needs guided choices; plain text is error-prone. Meta limits interactive controls.
- **Decision:** Use:
  - `SendInteractiveListRequest` for menus and option sets larger than 3.
  - `SendInteractiveButtonRequest` for up to 3 quick-reply buttons.
  - Plain `SendMessageRequest` for prompts and free-text input.
- **Consequences:**
  - Respect Meta limits: up to 10 list rows, up to 3 buttons, title/description length limits.
  - Keep interactive IDs stable and unique.
  - The webhook parser must handle both `interactive.list_reply` and `interactive.button_reply`.

### ADR-0006: Permissive Development Security

- **Status:** Inferred from Codebase
- **Context:** The project is developed locally with a Vue frontend and ngrok-exposed webhooks.
- **Decision:** `SecurityConfig` disables CSRF, permits all requests, and allows localhost/ngrok CORS origins. Controllers add local `@CrossOrigin` origins.
- **Consequences:**
  - This is a development posture, not a production security model.
  - Before production: add authentication/authorization, restrict origins, validate webhook signatures, and define a CSRF strategy.
  - Do not expose the REST API or webhook publicly without these controls.

### ADR-0007: PostgreSQL + Hibernate `create-drop` for Development

- **Status:** Inferred from Codebase
- **Context:** The project needs a relational store for patients, appointments, contacts, sessions, and messages.
- **Decision:** Use PostgreSQL with Spring Data JPA/Hibernate and `spring.jpa.hibernate.ddl-auto=create-drop`.
- **Consequences:**
  - Schema is generated automatically and data is dropped on restart.
  - This is not production-safe.
  - Add Flyway/Liquibase and use `validate`/`none` before production.

### ADR-0008: Configuration via Environment Placeholders

- **Status:** Inferred from Codebase
- **Context:** Meta tokens and database credentials must not be committed.
- **Decision:** Use `${ENV_VAR}` placeholders in `application.properties`, bind `whatsapp.*` in `WhatsAppProperties`, and keep `.env` gitignored.
- **Consequences:**
  - No code in this repository loads `.env`; export variables or use Docker Compose's `.env` interpolation.
  - The current `whatsapp.api.*` keys do not match the `WhatsAppProperties` field names; reconcile them before deployment.
  - Never commit real tokens or passwords.

### ADR-0009: Broad Domain Model, Narrow Runtime Usage

- **Status:** Inferred from Codebase
- **Context:** The JPA model includes entities for threads, bot commands, notifications, templates, and webhook events, but the current bot/controller code only actively uses a subset.
- **Decision:** Keep the broader model as scaffolding; rely on the actively wired entities (`Patient`, `Appointment`, `Dentist`, `Treatments`, `WhatsAppContact`, `WhatsAppSession`, `WhatsAppMessage`).
- **Consequences:**
  - Do not assume `message_threads`, `messages`, `bot_commands`, `notifications`, `whatsapp_templates`, or `whatsapp_webhook_events` are populated.
  - Wire unused entities deliberately before depending on them.
  - Future AI assistants should not invent behavior for modeled-but-unwired tables.

### ADR-0010: Auxiliary Frontends Outside the Backend Artifact

- **Status:** Inferred from Codebase
- **Context:** The repository contains a Vue app and a static clinic site in addition to the Spring Boot backend.
- **Decision:** Exclude `vue-project1/**` from Maven resources and keep `staticWebSite/` outside `src/main/resources`. The backend JAR contains only the Spring Boot application.
- **Consequences:**
  - Build and deploy the Vue app/static site separately.
  - Do not expect Spring Boot to serve those assets.

### ADR-0011: Immediate vs Deferred Persistence

- **Status:** Inferred from Codebase
- **Context:** The doctor registration and update flows have different persistence semantics.
- **Decision:**
  - Registration creates a `Patient` immediately and saves each field as it is received.
  - Updates accumulate changes in `DoctorSession.pendingChanges` and save only when the doctor taps `SI`.
- **Consequences:**
  - Registration can leave partial patients if the doctor abandons the flow.
  - Updates are atomic at confirmation time.
  - Do not assume registration is all-or-nothing; handle partial records.

### ADR-0012: In-Memory Search Over `findAll`

- **Status:** Inferred from Codebase
- **Context:** Search must match name, lastname, full name, phone, date, and treatment across patients and appointments.
- **Decision:** Load patients and appointments with `findAll()` and filter in memory; cap displayed results at 10.
- **Consequences:**
  - Simple and flexible for a small clinic dataset.
  - Does not scale to large datasets; add repository queries/indexes and pagination before growth.
  - Keep matching logic centralized in `searchPatientsAndAppointments`.

### ADR-0013: No Idempotency or Retry Enforcement (Open)

- **Status:** Inferred from Codebase
- **Context:** The webhook controller always acknowledges events with `200` and does not deduplicate them; `WhatsAppWebhookEvent.eventKey` is modeled with a unique constraint but is never checked.
- **Decision:** The webhook controller catches exceptions, logs them, and always returns `200`. `WhatsAppWebhookEvent.eventKey` exists with a unique constraint but is not used. `WhatsAppClient` does not retry.
- **Consequences:**
  - Duplicate processing is possible.
  - Transient Graph API failures are not retried.
  - Before production: add event deduplication via `WhatsAppWebhookEvent.eventKey`, retry/backoff, and observability.

### Proposed / Open ADR Topics

The following decisions should be recorded next:

- Webhook signature verification and app-secret management.
- Idempotent webhook processing and retry/backoff policy.
- Shared persistent storage for doctor conversation state (DB/Redis).
- Multi-doctor/multi-tenant authentication and authorization.
- Database migration strategy (Flyway/Liquibase) and production `ddl-auto`.
- REST API authentication and IDOR protection for patient/appointment endpoints.
- Template-message support (`WhatsAppTemplate`, `TemplateParameters`).
- Graph API rate limiting, circuit breaking, and dead-letter handling.
- Observability: structured logging, metrics, tracing, and alerting.
- Test strategy for bot flows and webhook parsing.
- Fixing the `whatsapp.api.*` vs `whatsapp.api-url` configuration mismatch.
- Verifying the native treatment-list projection used by the `Cobrar` flow.

---

## Contributing

- Keep webhook payload DTOs aligned with Meta's documented payload shapes.
- Do not bypass `WhatsAppBotService` from controllers for bot behavior.
- Preserve patient state transitions and null-safety around optional entity fields.
- Treat the doctor menu/update/registration flows as state machines; add tests when changing transitions.
- Never commit `.env`, access tokens, verify tokens, or database passwords.
- For backend changes, run:
  ```bash
  ./mvnw -DskipTests compile
  ./mvnw test
  ```
- For frontend changes, run the scripts in `src/main/resources/vue-project1/package.json`:
  ```bash
  npm run lint
  npm run type-check
  npm run test:unit
  ```
- Before production, address the open ADRs above, especially security, idempotency, migrations, and configuration binding.

---

## Known Unknowns / `[VERIFY IN CODE/ENV]`

Confirm these items before deploying or extending the system:

- The exact Meta app configuration (app ID, app secret, callback URL, subscribed fields, production phone number).
- Which Meta Graph API version is intended: `v21.0` in `application.properties` vs `v26.0` in `WhatsAppProperties`.
- The correct property-key strategy for `whatsapp.api.*` vs `whatsapp.api-url`, `whatsapp.phone-number-id`, etc.
- Whether `USERNAME_DB`, `PASSWORD_DB`, `PORT_DB`, `WHATSAPP_BUSINESS_PHONE_NUMBER_ID`, and `WHATSAPP_FLOW_*` in `.env` are still used locally.
- The exact international format of `DOCTOR_WHATSAPP_PHONE` expected by the current Meta account.
- Whether the native `getAllTreatmentsIgnoreCase` query maps correctly to `SendInteractiveListRequest.Row` in the target PostgreSQL schema.
- Whether the unmapped entities (`MessageThread`, `Message`, `BotCommand`, `Notification`, `WhatsAppTemplate`, `WhatsAppWebhookEvent`) are planned for future features.
- The Vue app's API base URL and deployment target.
- The purpose of the empty top-level `state/` directory.
- Production hosting, TLS termination, and webhook URL.
- Whether a test profile or dedicated test database exists; none was found in the repository.
