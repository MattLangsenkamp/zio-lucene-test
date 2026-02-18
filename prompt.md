# Wikipedia EventStream Ingestion - Implementation Plan

## Documentation References

**Primary References for Claude Code:**

- **Main EventStreams Docs**: https://wikitech.wikimedia.org/wiki/Event_Platform/EventStreams
- **OpenAPI Interactive Docs**: https://stream.wikimedia.org/?doc
- **OpenAPI Spec (JSON)**: https://stream.wikimedia.org/?spec
- **Schema Repository**: https://schema.wikimedia.org/#!/primary/jsonschema
- **RecentChange Schema**: https://schema.wikimedia.org/#!/primary/jsonschema/mediawiki/recentchange/latest.yaml
- **RCFeed Format Details**: https://www.mediawiki.org/wiki/Manual:RCFeed

---

## Project Overview

**Goal**: Implement Wikipedia EventStream ingestion service that pulls edit stream data, deserializes it to case classes, and logs events.

**Scope**: English Wikipedia only (`enwiki`), SSE events with JSON payloads containing edit metadata.

**Technology Stack**:
- Scala 3 with ZIO
- sttp for HTTP/SSE client
- zio-json for deserialization
- zio-opentelemetry for metrics (already configured)

---

## Milestones

### M1: Configuration Loading & Validation

**Goal**: Load and validate all required configuration from environment variables

**Acceptance Criteria:**
- [ ] Case class `WikipediaStreamConfig` defined with fields: `langNamespace`, `streamType`, `backoffStartMs`, `backoffIncrementMs`, `backoffMaxMs`
- [ ] Environment variables read: `WIKI_LANG`, `WIKI_STREAM`, `WIKI_BACKOFF_START_MS`, `WIKI_BACKOFF_INCREMENT_MS`, `WIKI_BACKOFF_MAX_MS`
- [ ] If ANY env var is missing: log error message with missing var name(s) and exit with non-zero status
- [ ] If ANY env var is malformed (e.g., non-numeric for backoff values): log error and exit with non-zero status
- [ ] If all env vars present and valid: log success message with loaded config values
- [ ] Config loading fails before any network calls are made

**QA Focus**: Test with missing vars, malformed vars, and valid config

---

### M2: Metadata Discovery & Stream Selection

**Goal**: Fetch stream metadata and validate configured stream exists

**Acceptance Criteria:**
- [ ] Case class(es) defined for metadata API response structure (examine https://stream.wikimedia.org/?spec to determine schema)
- [ ] Hardcoded metadata endpoint URL: `https://stream.wikimedia.org/?spec`
- [ ] HTTP GET request made to metadata endpoint on startup using sttp
- [ ] Response deserialized to case class using zio-json
- [ ] Validate that stream matching config (`langNamespace` + `streamType`) exists in metadata
- [ ] If stream not found in metadata: log error with attempted stream name and exit with non-zero status
- [ ] If metadata fetch fails (network error, timeout): log error and exit with non-zero status
- [ ] If deserialization fails: log error and exit with non-zero status
- [ ] On success: log confirmation with resolved stream URL (e.g., `https://stream.wikimedia.org/v2/stream/recentchange`)

**QA Focus**: Test with valid stream name, invalid stream name, unreachable endpoint, malformed response

---

### M3: SSE Connection & Raw Event Logging

**Goal**: Connect to EventStream and log raw events as strings

**Acceptance Criteria:**
- [ ] SSE connection established to resolved stream URL using sttp
- [ ] Each incoming SSE event logged as raw string
- [ ] Log includes SSE event type field if present (e.g., "message", "error")
- [ ] Connection remains open and continues receiving events
- [ ] If initial connection fails: proceed to M6 reconnection logic
- [ ] Service remains running while receiving events

**QA Focus**: Verify continuous stream of logs, check log format includes raw payload

---

### M4: Deserialization to Case Class

**Goal**: Parse SSE event JSON to structured case class

**Acceptance Criteria:**
- [ ] Case class `WikipediaEdit` defined matching EventStream JSON schema (reference: https://schema.wikimedia.org/#!/primary/jsonschema/mediawiki/recentchange/latest.yaml)
- [ ] Each raw event parsed using zio-json
- [ ] Successfully parsed events logged with structured fields (not raw JSON)
- [ ] Malformed JSON: log error with raw payload snippet + emit metric `wikipedia.deserialization.error{error_type="malformed_json"}`
- [ ] Schema mismatch (valid JSON but doesn't match case class): log error + emit metric `wikipedia.deserialization.error{error_type="schema_mismatch"}`
- [ ] Service continues processing subsequent events after deserialization errors
- [ ] No events are dropped silently - every event is either logged as success or error

**QA Focus**: Test with valid events, manually inject malformed JSON, verify metrics emitted

---

### M5: Error Metrics Implementation

**Goal**: Comprehensive error tracking via OpenTelemetry metrics

**Acceptance Criteria:**
- [ ] Metric: `wikipedia.deserialization.error` (Counter) with label `error_type` (values: "malformed_json", "schema_mismatch")
- [ ] Metric: `wikipedia.reconnection.attempt` (Counter)
- [ ] All metrics use existing zio-opentelemetry setup (no new configuration needed)
- [ ] Metrics visible in configured observability backend
- [ ] Each metric incremented at appropriate point in code

**QA Focus**: Trigger errors, verify metric increments in observability backend

---

### M6: Reconnection Logic

**Goal**: Handle connection failures with infinite linear backoff retries

**Acceptance Criteria:**
- [ ] On connection failure: log error with reason
- [ ] Emit metric `wikipedia.reconnection.attempt`
- [ ] First retry after `backoffStartMs` milliseconds
- [ ] Each subsequent retry adds `backoffIncrementMs` to previous delay
- [ ] Delay capped at `backoffMaxMs`
- [ ] Example: if start=1000, increment=1000, max=5000 → delays are 1s, 2s, 3s, 4s, 5s, 5s, 5s...
- [ ] Retries continue infinitely (no max attempt limit)
- [ ] On successful reconnection: log success message and reset backoff to start value
- [ ] Stream processing resumes from fresh (no attempt to catch up on missed events)

**QA Focus**: Simulate network failures, verify backoff timing, confirm infinite retries

---

### M7: Graceful Shutdown

**Goal**: Clean service shutdown on termination signals

**Acceptance Criteria:**
- [ ] Service responds to SIGTERM/SIGINT
- [ ] On shutdown signal: log shutdown initiated message
- [ ] SSE connection closed cleanly (no hanging connections)
- [ ] In-flight event processing allowed to complete (or define timeout)
- [ ] Final log message confirming shutdown complete
- [ ] Service exits with status 0 on graceful shutdown
- [ ] No state persisted (stateless shutdown for this phase)

**QA Focus**: Send SIGTERM, verify clean shutdown in logs, check no zombie connections

---

## Error Handling Philosophy

- **Malformed JSON**: Log error with payload snippet, increment metric, continue processing
- **Schema mismatch**: Log error, increment metric, continue processing
- **Connection failures**: Log error, increment metric, retry with linear backoff infinitely
- **Missing/malformed config**: Log error and exit immediately (fail fast)
- **Missing stream in metadata**: Log error and exit immediately (fail fast)
- **Partial success (bad data after 1000+ good events)**: Log error, increment metric, continue processing

---

## Open Questions to Resolve During Implementation

1. Exact metadata API response structure (discover during M2 using https://stream.wikimedia.org/?spec)
2. Exact EventStream JSON schema fields needed for case class (discover during M4 using https://schema.wikimedia.org/#!/primary/jsonschema/mediawiki/recentchange/latest.yaml)
3. In-flight event timeout during shutdown (M7)
4. Client-side filtering by `server_name` field (e.g., "en.wikipedia.org") based on `WIKI_LANG` - add to M4 or later phase?

---

## Configuration Reference

### Environment Variables

| Variable | Type | Example | Description |
|----------|------|---------|-------------|
| `WIKI_LANG` | String | `enwiki` | Wikipedia language/namespace |
| `WIKI_STREAM` | String | `recentchange` | Stream type to consume |
| `WIKI_BACKOFF_START_MS` | Long | `1000` | Initial retry delay in milliseconds |
| `WIKI_BACKOFF_INCREMENT_MS` | Long | `1000` | Delay increment per retry in milliseconds |
| `WIKI_BACKOFF_MAX_MS` | Long | `5000` | Maximum retry delay cap in milliseconds |

### Example Configuration

```bash
export WIKI_LANG=enwiki
export WIKI_STREAM=recentchange
export WIKI_BACKOFF_START_MS=1000
export WIKI_BACKOFF_INCREMENT_MS=1000
export WIKI_BACKOFF_MAX_MS=5000
```

---

## Notes

- **SSE Format**: Events are Server-Sent Events (SSE) over HTTP, with JSON payloads in the `data` field
- **Canary Events**: Wikipedia sends periodic "canary" events with `meta.domain == "canary"` for health checks - these should be filtered out
- **Connection Timeout**: Wikimedia enforces 15-minute connection timeout; reconnection logic handles this
- **No State Persistence**: This phase does not persist any state - each reconnection starts fresh
- **Server-side Filtering**: EventStreams does not support server-side filtering by wiki; must filter client-side by `server_name` field