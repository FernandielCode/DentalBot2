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
- Once the doctor sets a date, the bot schedules a 3-day and a 1-day patient reminder. Patients can tap **Confirmar** or **Cancelar**.
- Outbound replies are sent through `WhatsAppClient` to the Graph API endpoint `/{phone-number-id}/messages`.

### What it does **not** currently use

- No WhatsApp Flows DTOs or flow JSON resources are present in the current codebase.
- No WebSocket/messaging dependency is used.
- No inbound template-creation/approval automation is implemented; approved templates are still managed in Meta Business Manager.
- No Flyway/Liquibase migration tool is present.

### High-level request flow

```text
Meta WhatsApp Cloud API
        │  POST /api/whatsapp/webhook
        │  X-Hub-Signature-256: sha256=<hex>
        ▼
WhatsAppWebhookController
        │
        ├─ WhatsAppSignatureVerifier validates HMAC-SHA256
        │       └─ invalid/missing → 401 Unauthorized
        ▼
JsonMapper.readValue(rawBody, WhatsAppWebhookPayload.class)
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

Patient reminder replies are routed to `AppointmentConfirmationService` before the intake state machine. A separate scheduler (`AppointmentReminderScheduler`) sends due reminders from `appointment_reminders`; it runs outside the webhook request path.

### WhatsApp integration surface

| Surface | Implemented in | Notes |
|---|---|---|
| Webhook verification (`GET`) | `WhatsAppWebhookController.verifyWebhook` | Checks `hub.mode=subscribe` and `hub.verify_token == whatsapp.verify-token` |
| Webhook signature validation (`POST`) | `WhatsAppSignatureVerifier` | HMAC-SHA256 over the raw body; constant-time compare; fail-closed when `whatsapp.app-secret` is missing |
| Incoming messages (`POST`) | `WhatsAppWebhookController.receiveWebhookEvent` | Validates signature, parses raw body with Jackson 3 `JsonMapper`; returns `401` invalid signature, `400` malformed JSON, `200` authenticated events |
| Text replies | `SendMessageRequest` + `WhatsAppClient.sendTextMessage` | Basic text message |
| Interactive lists | `SendInteractiveListRequest` + `WhatsAppClient.sendInteractiveList` | Up to 10 rows; used for menus and field selection |
| Interactive reply buttons | `SendInteractiveButtonRequest` + `WhatsAppClient.sendInteractiveButtons` | Up to 3 buttons; used for `PACIENTE`/`CITA`, `SI`/`NO`, patient state |
| WhatsApp Flow messages | Not present | The codebase currently uses lists/buttons, not Flows |
| Template messages | `SendTemplateMessageRequest` + `WhatsAppClient.sendTemplateMessage` | Used for proactive reminders; the template must be approved in Meta Business Manager |

> `[VERIFY IN CODE/ENV]` The actual Meta app secret value, app ID, webhook callback URL, subscribed fields, and production phone number are external to this repository. The code expects `WHATSAPP_APP_SECRET` for POST signature validation.

---

## Tech Stack & Key Dependencies

### Backend

| Item | Value | Source |
|---|---|---|
| Java | 21 | `pom.xml` |
| Spring Boot | 4.1.1 | `pom.xml` parent |
| Web | `spring-boot-starter-webmvc` | `pom.xml` |
| JSON | Jackson 3 (`tools.jackson.databind.json.JsonMapper`) via `spring-boot-starter-jackson` | Dependency tree |
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
│   │   │       ├── WhatsAppSignatureVerifier.java # HMAC-SHA256 validation
│   │   │       └── PatientService.java         # Patient CRUD service
│   │   └── resources/
│   │       ├── application.properties
│   │       └── vue-project1/                   # Independent Vue app
│   └── test/java/com/example/Project1/
│       ├── Project1ApplicationTests.java       # contextLoads()
│       ├── Controller/WhatsAppWebhookControllerTest.java
│       └── Service/
│           ├── WhatsAppSignatureVerifierTest.java
│           └── WhatsAppBotServiceDateConfirmationTest.java
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
  - The Meta app secret (`WHATSAPP_APP_SECRET`) used for `X-Hub-Signature-256`.
- A public HTTPS URL for webhook delivery. The CORS config anticipates `ngrok` domains (`https://*.ngrok-free.app`, `https://*.ngrok.io`).

### Required environment variables (backend)

These are referenced by `src/main/resources/application.properties` or `compose.yaml`:

| Variable | Used by | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `application.properties` | JDBC URL for PostgreSQL |
| `WHATSAPP_PHONE_NUMBER_ID` | `application.properties` / `WhatsAppProperties` | Meta phone number ID |
| `WHATSAPP_ACCESS_TOKEN` | `application.properties` / `WhatsAppProperties` | Graph API bearer token |
| `WHATSAPP_VERIFY_TOKEN` | `application.properties` / `WhatsAppProperties` | Webhook verification token |
| `WHATSAPP_APP_SECRET` | `application.properties` / `WhatsAppProperties` | Meta app secret for `X-Hub-Signature-256` HMAC validation |
| `WHATSAPP_SIGNATURE_VERIFICATION_ENABLED` | `application.properties` / `WhatsAppProperties` | Optional dev toggle; defaults to `true` |
| `DOCTOR_WHATSAPP_PHONE` | `application.properties` | Doctor's WhatsApp number (`whatsapp.doctor.phone-number`) |
| `REMINDER_ENABLED` | `application.properties` | Enables/disables appointment reminders |
| `REMINDER_ZONE` | `application.properties` | Clinic timezone; default `America/Guayaquil` |
| `REMINDER_SEND_TIME` | `application.properties` | Reminder send time in clinic time; default `09:00` |
| `REMINDER_TEMPLATE_NAME` | `application.properties` | Approved Meta template name for reminders |
| `REMINDER_TEMPLATE_LANGUAGE` | `application.properties` | Template language code; default `es` |
| `REMINDER_TEMPLATE_CLINIC_NAME` | `application.properties` | Clinic name used as a template body parameter |
| `REMINDER_TEMPLATE_CONFIRM_LABEL` | `application.properties` | Confirm quick-reply label; default `Confirmar` |
| `REMINDER_TEMPLATE_CANCEL_LABEL` | `application.properties` | Cancel quick-reply label; default `Cancelar` |
| `REMINDER_TEMPLATE_ENABLED` | `application.properties` | Use approved templates; set `false` for local interactive-button fallback |
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

The new POST signature check expects `WHATSAPP_APP_SECRET`; add it to `.env` or your secret manager. If you cannot set it locally, set `WHATSAPP_SIGNATURE_VERIFICATION_ENABLED=false` for local development only.

### Configuration binding

`application.properties` now uses keys that bind to `WhatsAppProperties`:

```properties
whatsapp.api-url=https://graph.facebook.com/v21.0
whatsapp.phone-number-id=${WHATSAPP_PHONE_NUMBER_ID}
whatsapp.access-token=${WHATSAPP_ACCESS_TOKEN}
whatsapp.verify-token=${WHATSAPP_VERIFY_TOKEN}
whatsapp.app-secret=${WHATSAPP_APP_SECRET:}
whatsapp.signature-verification-enabled=${WHATSAPP_SIGNATURE_VERIFICATION_ENABLED:true}
```

`WhatsAppProperties` still declares a default `apiUrl` of `https://graph.facebook.com/v26.0`, but the property above overrides it. The effective Graph API version is therefore `v21.0`.

> `[VERIFY IN CODE/ENV]` Confirm `v21.0` is the intended Meta API version for this app.

Reminder settings bind to `ReminderProperties`:

```properties
app.reminders.enabled=${REMINDER_ENABLED:true}
app.reminders.zone=${REMINDER_ZONE:America/Guayaquil}
app.reminders.send-time=${REMINDER_SEND_TIME:09:00}
app.reminders.batch-size=${REMINDER_BATCH_SIZE:50}
app.reminders.max-attempts=${REMINDER_MAX_ATTEMPTS:3}
app.reminders.claim-timeout-minutes=${REMINDER_CLAIM_TIMEOUT_MINUTES:10}
app.reminders.poll-interval-ms=${REMINDER_POLL_INTERVAL_MS:60000}
app.reminders.rebuild-cron=${REMINDER_REBUILD_CRON:0 15 0 * * *}
app.reminders.template.enabled=${REMINDER_TEMPLATE_ENABLED:true}
app.reminders.template.name=${REMINDER_TEMPLATE_NAME:appointment_reminder_es}
app.reminders.template.language=${REMINDER_TEMPLATE_LANGUAGE:es}
app.reminders.template.clinic-name=${REMINDER_TEMPLATE_CLINIC_NAME:Dental Clinic}
app.reminders.template.confirm-label=${REMINDER_TEMPLATE_CONFIRM_LABEL:Confirmar}
app.reminders.template.cancel-label=${REMINDER_TEMPLATE_CANCEL_LABEL:Cancelar}
```

The production template must be approved in Meta Business Manager and contain two quick-reply buttons with the configured labels. For local development without an approved template, set `REMINDER_TEMPLATE_ENABLED=false`; the bot then uses interactive buttons and logs simulated sends when Meta credentials are absent.

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
$env:WHATSAPP_APP_SECRET    = "<meta-app-secret>"
$env:DOCTOR_WHATSAPP_PHONE  = "<doctor-whatsapp-number>"
# Local-only option if you do not have the Meta app secret yet:
# $env:WHATSAPP_SIGNATURE_VERIFICATION_ENABLED = "false"

# 3. Run the backend
.\mvnw.cmd spring-boot:run
```

No code in this repository loads `.env`; export variables in your shell or configure them in your IDE/run configuration.

### Run locally (Unix-like shell)

```bash
docker compose up -d

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/<database>"
export WHATSAPP_PHONE_NUMBER_ID="<meta-phone-number-id>"
export WHATSAPP_ACCESS_TOKEN="<meta-access-token>"
export WHATSAPP_VERIFY_TOKEN="<webhook-verify-token>"
export WHATSAPP_APP_SECRET="<meta-app-secret>"
export DOCTOR_WHATSAPP_PHONE="<doctor-whatsapp-number>"
# Local-only option if you do not have the Meta app secret yet:
# export WHATSAPP_SIGNATURE_VERIFICATION_ENABLED="false"

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
  -e WHATSAPP_APP_SECRET="<meta-app-secret>" \
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
4. Set `WHATSAPP_APP_SECRET` to the Meta app secret. The POST handler validates `X-Hub-Signature-256`; invalid signatures return `401`.
5. Subscribe to the `messages` webhook field (the code reads `change.value.messages`).
6. Send a WhatsApp message to the business number and confirm the webhook logs.

> `[VERIFY IN CODE/ENV]` The exact Meta app/webhook configuration, app secret value, and production hostname are external to this repository.

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

Current backend tests:

- `Project1ApplicationTests.contextLoads()` boots the full application context and needs the datasource configuration to be resolvable.
- `WhatsAppSignatureVerifierTest` covers valid, invalid, missing, and malformed `X-Hub-Signature-256` values.
- `WhatsAppWebhookControllerTest` covers `401`, `400`, and `200` behavior with `MockMvc`.
- `WhatsAppWebhookPayloadTest` covers template quick-reply parsing and `context.id`.
- `WhatsAppBotServiceDateConfirmationTest` verifies that the doctor's selected appointment is persisted and that only that appointment is confirmed.
- `AppointmentReminderServiceTest` covers reminder creation, short-notice skipping, delivery state, and the rule that a confirmed patient still receives the later 1-day reminder.
- `AppointmentConfirmationServiceTest` covers Confirmar/Cancelar, phone ownership checks, and doctor cancellation alerts.

Run the unit tests without a database:

```bash
./mvnw -Dtest=WhatsAppSignatureVerifierTest,WhatsAppWebhookControllerTest,WhatsAppWebhookPayloadTest,WhatsAppBotServiceDateConfirmationTest,AppointmentReminderServiceTest,AppointmentConfirmationServiceTest test
```

On Windows:

```powershell
.\mvnw.cmd "-Dtest=WhatsAppSignatureVerifierTest,WhatsAppWebhookControllerTest,WhatsAppWebhookPayloadTest,WhatsAppBotServiceDateConfirmationTest,AppointmentReminderServiceTest,AppointmentConfirmationServiceTest" test
```

---

## Webhook & API Flow

### Webhook endpoints

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/api/whatsapp/webhook` | Meta verification. Requires `hub.mode=subscribe` and a matching `hub.verify_token`; returns `hub.challenge` or `403`. |
| `POST` | `/api/whatsapp/webhook` | Validates `X-Hub-Signature-256` with `WhatsAppSignatureVerifier`, parses the raw body with Jackson 3, and calls `WhatsAppBotService.processWebhookEvent`. Returns `401` for invalid signatures, `400` for malformed JSON, and `200` for authenticated events. |

### Incoming payload handling

`WhatsAppWebhookPayload` is a record tree with `entry → changes → value → messages`.

`WhatsAppWebhookPayload.IncomingMessage` exposes:

- `getExtractedText()`: returns `text.body`, the title of an interactive list/button reply, or the template quick-reply payload/text.
- `getInteractiveSelectionId()`: returns the selected list/button ID or template quick-reply payload.
- `getContextMessageId()`: returns `context.id`, the message id a user replied to. Template reminder replies use this to identify the exact reminder.

Before parsing, `receiveWebhookEvent` receives the raw `byte[]` body and the `X-Hub-Signature-256` header. `WhatsAppSignatureVerifier` computes HMAC-SHA256 using `whatsapp.app-secret` and compares in constant time. If `whatsapp.signature-verification-enabled=false`, the check is skipped for local development. After validation, the auto-configured Jackson 3 `JsonMapper` parses the same bytes into `WhatsAppWebhookPayload`.

The bot processes each message as follows:

1. Creates or updates a `WhatsAppContact` by `wa_id`.
2. Saves the incoming message in `whatsapp_messages`.
3. If `from` equals `whatsapp.doctor.phone-number`, routes to `handleDoctorMessage`.
4. If the message is a template quick reply or a reminder interactive button, routes to `AppointmentConfirmationService`.
5. Otherwise, runs the patient intake state machine.

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
| `hola`, `menu`, `opciones` | Sends the main interactive menu: `Buscar`, `Cambiar`, `Cobrar`, `Pacientes`, `Fechas`, `Registrar paciente`. |
| `citas` | Paginated appointment list (10 per page) with a `Ver más citas` button. |
| `pago` or menu `Cobrar` | Sends the treatment list, then the legacy payment-method list. |
| `fechas` or menu `Fechas` | Lists all appointments waiting for a doctor-assigned date, oldest request first. |
| Menu `Buscar` | Asks for a search query; searches name, lastname, full name, phone, date, and treatment. |
| Menu `Cambiar` | Sends `PACIENTE` / `CITA` buttons, then the search flow, then the update field flow. |
| Menu `Pacientes` | Paginated patient list (10 per page) with a `Ver más pacientes` button. |
| Menu `Registrar paciente` | Sequential patient registration (name, lastname, phone, age, patient state, payment state). |
| Date confirmation | The doctor taps **Asignar fecha** or uses **Fechas**, selects a specific appointment, then sends a date. The appointment is confirmed by id, not by "latest PENDING". |

### Date confirmation flow

- When a patient completes intake, the new `Appointment` gets `awaitingDateConfirmation = true` and `dateConfirmationRequestedAt`.
- The doctor receives an **Asignar fecha** button carrying `DATE_CONFIRM_<appointmentId>`.
- `fechas` / **Fechas** lists every appointment with `awaitingDateConfirmation = true`, oldest first.
- Selecting one persists the appointment id in the doctor's `WhatsAppSession`:
  - `currentState = DOC_AWAITING_CONFIRMATION_DATE`
  - `contextJSON = <appointmentId>`
- The doctor's next date text updates that exact appointment, clears `awaitingDateConfirmation`, and clears the doctor's saved selection.
- If the doctor types a date without selecting an appointment, the bot shows the waiting list instead of guessing.
- After the date is set, the bot resets the patient confirmation state and schedules/refreshes the 3-day and 1-day reminders.
- Both the appointment flag and the doctor's selected appointment survive application restarts.

### Patient reminders and one-tap confirmation

- Reminders are persisted in `appointment_reminders`, one row per appointment and reminder type.
- `AppointmentReminderScheduler` polls due rows, claims them under a write lock, and sends them outside the claim transaction.
- The 3-day and 1-day reminders are sent at `app.reminders.send-time` in `app.reminders.zone`.
- Short-notice appointments only receive reminders whose scheduled time is still in the future.
- After a successful reminder, the appointment moves to `WAITING_PATIENT_ANSWER` so the clinic can track non-responses.
- A patient confirmation at the 3-day reminder **does not cancel** the 1-day reminder. The later reminder is still sent as a safety net.
- Tapping **Confirmar** sets `patientConfirmationStatus = CONFIRMED` and records `patientRespondedAt`.
- Tapping **Cancelar** sets `patientConfirmationStatus = CANCELLED`, sets `appointmentStatus = CANCELLED`, cancels open reminders, and notifies the doctor.
- A patient can cancel after confirming. Repeated Confirmar/Cancelar taps are handled idempotently.
- Only the phone registered on the appointment may confirm or cancel it.
- Delivery retries follow `app.reminders.max-attempts`; stale `SENDING` claims are requeued after `app.reminders.claim-timeout-minutes`.
- The nightly rebuild job creates missing reminders for appointments created or changed outside the bot; it never resets an already `SENT` row.

Production template requirement:

- Create/approve a utility template in Meta Business Manager with body parameters for patient name, clinic name, and appointment date.
- Add two quick-reply buttons (for example, `Confirmar` and `Cancelar`).
- Set `REMINDER_TEMPLATE_NAME`, `REMINDER_TEMPLATE_LANGUAGE`, and the button labels in the environment.
- The code sends only the body parameters; Meta stores the approved template and its buttons.

### Doctor conversation states (mostly in-memory)

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

These in-memory states are **not persisted**. They are lost on application restart and are not shared across instances.

The exception is the date-confirmation selection: the selected appointment id is stored in `whatsapp_sessions` with `currentState = DOC_AWAITING_CONFIRMATION_DATE` and `contextJSON = <appointmentId>`. It survives restarts, and the patient/appointment waiting state is stored on the `Appointment` row.

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
| `appointments` | `Appointment` | Yes (includes date confirmation and patient confirmation fields) |
| `appointment_reminders` | `AppointmentReminder` | Yes (3-day/1-day delivery state, retries, provider message ids) |
| `dentists` | `Dentist` | Yes (active dentist lookup, update validation) |
| `treatments` | `Treatments` | Yes (list/price lookup, update validation) |
| `appointment_treatments` | Join table | Yes |
| `whatsapp_contacts` | `WhatsAppContact` | Yes |
| `whatsapp_sessions` | `WhatsAppSession` | Yes (patient intake `currentState`; doctor date-confirmation selection) |
| `whatsapp_messages` | `WhatsAppMessage` | Yes (message audit) |
| `message_threads` | `MessageThread` | Modeled; no bot/controller references found |
| `messages` | `Message` | Modeled; no bot/controller references found |
| `bot_commands` | `BotCommand` | Modeled; no bot/controller references found |
| `notifications` | `Notification` | Modeled; no bot/controller references found |
| `whatsapp_templates` | `WhatsAppTemplate` | Modeled; no bot/controller references found |
| `whatsapp_webhook_events` | `WhatsAppWebhookEvent` | Modeled; no bot/controller references found |

The date-confirmation and reminder state lives in:

- `appointments.awaiting_date_confirmation` and `appointments.date_confirmation_requested_at` — which appointments are waiting for a doctor date.
- `appointments.patient_confirmation_status` and `appointments.patient_responded_at` — the patient’s reminder response.
- `appointment_reminders` — one row per reminder type with delivery status, retries, and the Meta message id.
- `whatsapp_sessions.current_state = DOC_AWAITING_CONFIRMATION_DATE` and `whatsapp_sessions.context_json = <appointmentId>` — which appointment the doctor is currently assigning a date to.

Production schema change:

```sql
ALTER TABLE appointments
    ADD COLUMN awaiting_date_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN date_confirmation_requested_at TIMESTAMP NULL,
    ADD COLUMN patient_confirmation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN patient_responded_at TIMESTAMP NULL;

CREATE TABLE appointment_reminders (
    reminder_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL REFERENCES appointments(appointment_id),
    reminder_type VARCHAR(20) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_message_id VARCHAR(255),
    sent_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    next_attempt_at TIMESTAMP,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_appointment_reminder_type UNIQUE (appointment_id, reminder_type)
);

CREATE INDEX idx_appointment_reminders_due
    ON appointment_reminders (status, next_attempt_at);

CREATE INDEX idx_appointment_reminders_provider_message
    ON appointment_reminders (provider_message_id);

CREATE INDEX idx_appointments_start_at
    ON appointments (start_appointment_at);
```

### Domain enums

| Enum | Values |
|---|---|
| `PatientState` | `UNDEFINED` (`POR DEFINIR`), `ON_TRACKING` (`EN SEGUIMIENTO`), `CLOSED` (`CERRADO`) |
| `PatientPaymentState` | `COMPLETE` (`COMPLETO`), `PARTIALLY` (`PARCIALMENTE`), `IN_DEBT` (`EN DEUDA`), `UNDEFINED_PRICE` (`POR DEFINIR`) |
| `PaymentMethod` | `CASH` (`EFECTIVO`), `TRANSFER` (`TRANSFERENCIA`) |
| `PatientConfirmationStatus` | `PENDING`, `WAITING_PATIENT_ANSWER`, `CONFIRMED`, `CANCELLED` |
| `ReminderType` | `THREE_DAYS`, `ONE_DAY` |
| `ReminderStatus` | `PENDING`, `SENDING`, `SENT`, `FAILED`, `CANCELLED` |

---

## Testing & Troubleshooting

### Running tests

```bash
./mvnw test
```

- `Project1ApplicationTests.contextLoads()` starts the full Spring context, so the datasource configuration must be resolvable.
- `WhatsAppSignatureVerifierTest` and `WhatsAppWebhookControllerTest` cover signature validation and webhook status codes.
- `WhatsAppWebhookPayloadTest` covers template quick-reply parsing and `context.id`.
- `WhatsAppBotServiceDateConfirmationTest` covers the persisted doctor selection and the appointment-specific date confirmation.
- `AppointmentReminderServiceTest` covers reminder creation, short-notice skipping, delivery state, and the later 1-day reminder rule.
- `AppointmentConfirmationServiceTest` covers Confirmar/Cancelar, phone ownership checks, and doctor cancellation alerts.
- There are no end-to-end bot-flow integration tests yet.

### Common pitfalls

| Symptom | Likely cause | What to check |
|---|---|---|
| Webhook verification returns `403` | Token mismatch | `WHATSAPP_VERIFY_TOKEN` must equal the Meta dashboard verify token |
| Outbound Meta calls fail or use placeholder values | Missing or wrong Meta credentials | Confirm `whatsapp.api-url`, `whatsapp.phone-number-id`, `whatsapp.access-token`, and `whatsapp.verify-token` are set and bound |
| Outbound Meta calls fail with an auth/permission error | Meta access token expired or invalid | Refresh `WHATSAPP_ACCESS_TOKEN` and restart the app |
| Every POST returns `401` | Signature verification enabled but `WHATSAPP_APP_SECRET` is missing or wrong | Set the Meta app secret, or set `WHATSAPP_SIGNATURE_VERIFICATION_ENABLED=false` locally |
| Data disappears after restart | `spring.jpa.hibernate.ddl-auto=create-drop` | Use migrations and a non-destructive setting for production |
| Doctor commands do not trigger | `DOCTOR_WHATSAPP_PHONE` does not exactly match the incoming `from` value | Confirm the international number format used by Meta |
| Search/update/register flow resets | Those flows use in-memory `DoctorSession` state | Restart clears them; the date-confirmation selection is persisted separately |
| Reminders do not send | Scheduler disabled, no eligible appointment, or a past short-notice reminder | Check `REMINDER_ENABLED`, `app.reminders.send-time`, `appointment_reminders`, and application logs |
| Template reminder fails | Template not approved, wrong name/language, or malformed parameters | Verify the approved Meta template and the `REMINDER_TEMPLATE_*` values |
| Confirmar/Cancelar does nothing | Template quick reply did not include `context.id`, or the reminder is not `SENT` | Check `appointment_reminders.provider_message_id` and the incoming `context.id` |
| Confirmed patient still receives the 1-day reminder | This is intentional | It is a safety net; do not change without reopening the reminder decision |
| `awaiting_date_confirmation` column missing | Production DB schema not migrated | Add the new `appointments` columns or run the migration; dev uses `ddl-auto=create-drop` |
| Duplicate replies for one Meta event | Authenticated events return `200`; no idempotency check | Implement `WhatsAppWebhookEvent.eventKey` deduplication |
| Webhook signature mismatch | Wrong app secret, altered/re-encoded body, or a proxy changing the payload | Ensure the raw body reaches the app unchanged; verify `WHATSAPP_APP_SECRET` |
| REST API is publicly readable/writable | `SecurityConfig` permits all requests | Add authentication/authorization before production |
| `Cobrar` flow behaves unexpectedly | Legacy PAGO interaction has no dedicated state machine | Review `handleLegacyTreatmentSelection` before relying on it |
| Treatment list fails to map | Native query returns `SendInteractiveListRequest.Row` | `[VERIFY IN CODE/ENV]` the native projection against the PostgreSQL schema |
| Vue app not served by Spring Boot | `vue-project1/**` is excluded from Maven resources | Run the Vue dev server separately |
| `ngrok` URL changed | Meta still points to the old host | Update the callback URL in Meta and restart the tunnel |

### Security notes

- Webhook `POST` requests are authenticated with `X-Hub-Signature-256` (HMAC-SHA256) via `WhatsAppSignatureVerifier`; invalid signatures return `401`.
- Signature validation is fail-closed by default. The dev toggle `WHATSAPP_SIGNATURE_VERIFICATION_ENABLED=false` must never be used in production.
- `SecurityConfig` disables CSRF and permits **all** requests.
- CORS allows `http://localhost:*`, `https://*.ngrok-free.app`, and `https://*.ngrok.io`.
- `PatientController` and `AppointmentController` add local dev `@CrossOrigin` origins (`localhost:5173`, `:3000`, `:5157`).
- The REST API still has **no authentication**, and CSRF is disabled. Harden these before production.
- These choices are suitable for local development except for the webhook signature check, which is production-oriented.

---

## Architectural Decision Records (ADRs)

> No formal `docs/` or `adr/` directory exists in this repository. The records below are **inferred from the codebase** and describe the patterns that future contributors and AI assistants must preserve unless they intentionally replace them.

### ADR Index

| ADR | Title | Status |
|---|---|---|
| ADR-0001 | Layered Spring Boot Monolith | Inferred from Codebase |
| ADR-0002 | Webhook-First Meta Integration with `RestClient` | Inferred from Codebase |
| ADR-0003 | Hybrid Conversation State | Inferred from Codebase |
| ADR-0004 | Doctor Identity by Configured Phone Number | Inferred from Codebase |
| ADR-0005 | Interactive Lists and Buttons Instead of Flows | Inferred from Codebase |
| ADR-0006 | Permissive REST Security with Signed Webhooks | Inferred from Codebase |
| ADR-0007 | PostgreSQL + Hibernate `create-drop` for Development | Inferred from Codebase |
| ADR-0008 | Configuration via Environment Placeholders | Inferred from Codebase |
| ADR-0009 | Broad Domain Model, Narrow Runtime Usage | Inferred from Codebase |
| ADR-0010 | Auxiliary Frontends Outside the Backend Artifact | Inferred from Codebase |
| ADR-0011 | Immediate vs Deferred Persistence | Inferred from Codebase |
| ADR-0012 | In-Memory Search Over `findAll` | Inferred from Codebase |
| ADR-0013 | No Idempotency or Retry Enforcement (Open) | Inferred from Codebase |
| ADR-0014 | Webhook HMAC-SHA256 Signature Validation | Accepted |
| ADR-0015 | Persistent Per-Appointment Date Confirmation | Accepted |
| ADR-0016 | Persisted Appointment Reminders and Patient Confirmation | Accepted |

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
  - POST authenticity is handled separately by HMAC-SHA256 signature validation (ADR-0014); the GET verify token is not a substitute for it.

### ADR-0003: Hybrid Conversation State

- **Status:** Inferred from Codebase
- **Context:** Both patient intake and doctor administrative workflows span multiple messages.
- **Decision:**
  - Patient state is persisted in `WhatsAppSession.currentState` (`INITIAL`, `AWAITING_PATIENT_INFO`, `COMPLETED`).
  - Doctor search/update/register flows are kept in an in-memory `ConcurrentHashMap<String, DoctorSession>` keyed by doctor phone.
  - The date-confirmation selection is persisted in a doctor-level `WhatsAppSession` (`currentState = DOC_AWAITING_CONFIRMATION_DATE`, `contextJSON = appointmentId`).
- **Consequences:**
  - Patient state and the selected date-confirmation appointment survive application restarts.
  - Search/update/register doctor flows are lost on restart and are not safe across multiple instances without shared state.
  - Preserve state transitions and reset doctor state on the global `hola` escape hatch.
  - Clear the persisted date selection when the doctor switches to another flow (`citas`, `pago`, menu, `fechas`).

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

### ADR-0006: Permissive REST Security with Signed Webhooks

- **Status:** Inferred from Codebase
- **Context:** The project is developed locally with a Vue frontend and ngrok-exposed webhooks. Meta POSTs must be authenticated independently of Spring Security.
- **Decision:**
  - `SecurityConfig` disables CSRF, permits all requests, and allows localhost/ngrok CORS origins. Controllers add local `@CrossOrigin` origins.
  - Webhook POSTs are authenticated by `WhatsAppSignatureVerifier` using HMAC-SHA256 over the raw body (ADR-0014).
- **Consequences:**
  - The webhook has a production-oriented authenticity check.
  - The REST API still has no authentication/authorization, CSRF is disabled, and CORS is development-oriented.
  - Before production: add REST authentication/authorization, restrict origins, and define a CSRF strategy.
  - Do not expose the REST API publicly without these controls.

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
  - Property keys are aligned with `WhatsAppProperties`: `whatsapp.api-url`, `whatsapp.phone-number-id`, `whatsapp.access-token`, `whatsapp.verify-token`, `whatsapp.app-secret`, and `whatsapp.signature-verification-enabled`.
  - Never commit real tokens, app secrets, or passwords.

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
- **Context:** The webhook controller acknowledges authenticated events with `200` and does not deduplicate them; `WhatsAppWebhookEvent.eventKey` is modeled with a unique constraint but is never checked.
- **Decision:** Invalid signatures return `401` and malformed JSON returns `400` before processing. Authenticated events still return `200` even if bot processing fails. `WhatsAppWebhookEvent.eventKey` exists with a unique constraint but is not used. `WhatsAppClient` does not retry.
- **Consequences:**
  - Duplicate processing is possible.
  - Transient Graph API failures are not retried.
  - Before production: add event deduplication via `WhatsAppWebhookEvent.eventKey`, retry/backoff, and observability.

### ADR-0014: Webhook HMAC-SHA256 Signature Validation

- **Status:** Accepted
- **Context:** Meta POSTs must be authenticated. The previous controller deserialized the body immediately and always returned `200`, so any caller could submit a payload.
- **Decision:**
  - `WhatsAppWebhookController.receiveWebhookEvent` accepts the raw `byte[]` body and the `X-Hub-Signature-256` header.
  - `WhatsAppSignatureVerifier` computes HMAC-SHA256 with `whatsapp.app-secret` and compares using `MessageDigest.isEqual`.
  - Invalid or missing signatures return `401`; malformed JSON returns `400`; authenticated processing failures return `200`.
  - Verification is enabled by default and fails closed when the secret is missing. `whatsapp.signature-verification-enabled=false` is a local-development escape hatch only.
- **Consequences:**
  - The raw body must not be modified or re-encoded before verification.
  - Proxies must preserve the exact request body.
  - `WHATSAPP_APP_SECRET` must be provisioned in every environment where POSTs are accepted.
  - Signature validation does not provide replay protection or idempotency; those remain part of ADR-0013.

### ADR-0015: Persistent Per-Appointment Date Confirmation

- **Status:** Accepted
- **Context:** The previous date-confirmation flow used a single in-memory `isWaitingDate` boolean and `findTopByAppointmentStatusOrderByCreatedAtDesc("PENDING")`. With two patients registering close together, the doctor's date could be applied to the wrong appointment. The flag was also lost on restart.
- **Decision:**
  - Store `awaitingDateConfirmation` and `dateConfirmationRequestedAt` on `Appointment`.
  - When a patient registers, mark that appointment as awaiting confirmation and send the doctor an `Asignar fecha` button carrying `DATE_CONFIRM_<appointmentId>`.
  - Add a `fechas` command and `Fechas` menu option to list waiting appointments oldest-first.
  - Persist the doctor's selected appointment in `WhatsAppSession` (`currentState = DOC_AWAITING_CONFIRMATION_DATE`, `contextJSON = appointmentId`).
  - Confirm the appointment by the selected id; never fall back to "latest PENDING".
  - Clear the appointment flag and doctor selection after confirmation or when the doctor switches flows.
- **Consequences:**
  - Multiple patients can be waiting without ambiguity; the doctor must select one.
  - The waiting state and selected appointment survive restarts.
  - The `appointments` table needs new columns in production; `ddl-auto=create-drop` handles this in dev only.
  - The doctor must tap `Asignar fecha`/`Fechas` before sending a date; free-text dates without a selection show the waiting list.

### ADR-0016: Persisted Appointment Reminders and Patient Confirmation

- **Status:** Accepted
- **Context:** Patients need automatic reminders 3 days and 1 day before the appointment, with one-tap Confirmar/Cancelar. Proactive messages outside WhatsApp’s 24-hour service window require approved templates. A patient may also have multiple appointments, so reminder replies must identify the exact appointment safely.
- **Decision:**
  - Persist delivery state in `appointment_reminders`, unique by `(appointment_id, reminder_type)`.
  - Schedule reminders from `Appointment.startAppointmentAt` at the configured clinic time and timezone.
  - Poll due rows with `AppointmentReminderScheduler`; claim `PENDING` rows under a write lock, then send in a separate transaction.
  - Store Meta’s provider message id on the reminder. Template quick-reply replies are correlated through `context.id`.
  - Keep patient confirmation separate from `appointmentStatus` with `PatientConfirmationStatus`.
  - Mark `WAITING_PATIENT_ANSWER` after a successful send so no-response patients can be tracked.
  - Do not cancel the 1-day reminder when the patient confirms the 3-day reminder; the later reminder remains a safety net.
  - On Cancelar, set `appointmentStatus = CANCELLED`, cancel open reminders, and notify the doctor.
  - Only the phone registered on the appointment may confirm or cancel it.
  - Use an approved Meta template in production and interactive buttons only as a local-development fallback.
- **Consequences:**
  - Reminders survive restarts and can be retried without duplicate sends for the same date.
  - The `appointments` and `appointment_reminders` tables need production migrations.
  - Production setup requires an approved utility template with two quick-reply buttons.
  - Doctor cancellation alerts use free-form text; a separate approved doctor-alert template may be needed outside the 24-hour window.
  - The nightly rebuild job only creates missing reminders; it does not recreate or reset `SENT` rows.

### Proposed / Open ADR Topics

The following decisions should be recorded next:

- Idempotent webhook processing and retry/backoff policy.
- Persisting search/update/register doctor conversation state (DB/Redis) for multi-instance safety.
- Multi-doctor/multi-tenant authentication and authorization.
- Database migration strategy (Flyway/Liquibase) and production `ddl-auto`.
- REST API authentication and IDOR protection for patient/appointment endpoints.
- Doctor cancellation alert template for outside the 24-hour WhatsApp window.
- Reminder delivery-status webhooks and dead-letter handling.
- Graph API rate limiting, circuit breaking, and dead-letter handling.
- Observability: structured logging, metrics, tracing, and alerting.
- Test strategy for bot flows and webhook parsing.
- Secret rotation and storage policy for `WHATSAPP_APP_SECRET`.
- Verifying the native treatment-list projection used by the `Cobrar` flow.

---

## Contributing

- Keep webhook payload DTOs aligned with Meta's documented payload shapes.
- Do not bypass `WhatsAppBotService` from controllers for bot behavior.
- Preserve patient state transitions and null-safety around optional entity fields.
- Treat the doctor menu/update/registration flows as state machines; add tests when changing transitions.
- Never commit `.env`, access tokens, verify tokens, app secrets, or database passwords.
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

- The exact Meta app configuration (app ID, app secret value, callback URL, subscribed fields, production phone number).
- Which Meta Graph API version is intended: the effective value is `v21.0` from `application.properties`; `WhatsAppProperties` still has a `v26.0` default.
- Whether `USERNAME_DB`, `PASSWORD_DB`, `PORT_DB`, `WHATSAPP_BUSINESS_PHONE_NUMBER_ID`, and `WHATSAPP_FLOW_*` in `.env` are still used locally.
- Whether a production secret-rotation/storage policy exists for `WHATSAPP_APP_SECRET`.
- The exact approved reminder template name, language, button labels, and approval status in Meta Business Manager.
- Whether the approved reminder template returns `context.id` on quick-reply taps; if not, the correlation strategy must switch to URL buttons or another token.
- The exact international format of `DOCTOR_WHATSAPP_PHONE` expected by the current Meta account.
- Whether the native `getAllTreatmentsIgnoreCase` query maps correctly to `SendInteractiveListRequest.Row` in the target PostgreSQL schema.
- Whether the unmapped entities (`MessageThread`, `Message`, `BotCommand`, `Notification`, `WhatsAppTemplate`, `WhatsAppWebhookEvent`) are planned for future features.
- The Vue app's API base URL and deployment target.
- The purpose of the empty top-level `state/` directory.
- Production hosting, TLS termination, and webhook URL.
- Whether a test profile or dedicated test database exists; none was found in the repository.
