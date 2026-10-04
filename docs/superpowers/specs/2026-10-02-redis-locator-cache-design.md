# Redis locator cache

Date: 2026-10-02

## Outcome

Share learned locators across machines and CI shards so Alumnium is called once per broken locator, not once per JVM. The local JSON file stays the default. Redis is reached only through a small HTTP service that lives in this repo, in the package `tech.ishabbi.graft.cache`. There is no second Gradle module and no move of the existing sources.

Success: a test JVM with `GRAFT_LEARNED_URL` set replays a suggestion written by another JVM, a newer heal is not deleted by an older miss, and a down cache does not fail the test.

## What stays

- Artifact `tech.ishabbi:graft`, sources at the repo root.
- Default store: `.graft/learned-locators.json`, overridable with `GRAFT_LEARNED`, disabled with `GRAFT_LEARNED=none`.
- `LearnedLocators.at(Path)`, `disabled()`, `get`, `learn`, `forget`, `snapshot`, and the JSON file shape used by `LearnedLocatorsTest`.
- Heal order: primary locator, then learned, then Alumnium. The store is consulted only after the primary misses.
- The heal report stays a local file. This design does not host it.

## Package

All cache types live in `tech.ishabbi.graft.cache`.

| Type | Role |
|---|---|
| `LearnedLocatorStore` | `get`, `learn`, `forget`, `snapshot` |
| `FileLocatorStore` | Today's JSON file. Default when no URL is set. |
| `HttpLocatorStore` | JDK `HttpClient` client of the cache service. Used when `GRAFT_LEARNED_URL` is set. |
| `CacheServer` | `main`. JDK `HttpServer`. The process you run. |
| `RedisLocatorStore` | Redis implementation of `LearnedLocatorStore`. Used only by `CacheServer`. |

`LearnedLocators` remains the type `AbstractHealer` and `HealingConfig` already use. It becomes a thin facade over a `LearnedLocatorStore`: the file implementation moves into `FileLocatorStore`, and `LearnedLocators.at` / `disabled` delegate to it. Existing call sites keep compiling.

`HealingConfig.Builder` gains `learnedStore(LearnedLocatorStore)` for tests. When that is set, it wins over path and URL.

## How the test JVM chooses a store

| Config | Store |
|---|---|
| `learnedStore(...)` on the builder | That instance |
| `GRAFT_LEARNED_URL` set | `HttpLocatorStore` |
| `GRAFT_LEARNED=none` and no URL | `LearnedLocators.disabled()` |
| otherwise | `FileLocatorStore` at `GRAFT_LEARNED` or `.graft/learned-locators.json` |

A set URL wins over `GRAFT_LEARNED`, including `none`.

| Env (test JVM) | Meaning |
|---|---|
| `GRAFT_LEARNED_URL` | Base URL of the cache service, no trailing path. Example: `http://127.0.0.1:8741` |
| `GRAFT_NAMESPACE` | Namespace. Default `default`. Pattern `[A-Za-z0-9._-]{1,64}` |
| `GRAFT_CACHE_TOKEN` | If set, sent as `Authorization: Bearer` |
| `GRAFT_LEARNED_TIMEOUT_MS` | Connect and request timeout for the HTTP client. Default `500` |

## HTTP API

`{key}` is the locator key, percent-encoded (it contains `:`, spaces, and quotes).

```
GET    /v1/namespaces/{ns}/entries/{key}
PUT    /v1/namespaces/{ns}/entries/{key}
DELETE /v1/namespaces/{ns}/entries/{key}?ifLearnedAt={ts}
GET    /v1/namespaces/{ns}/entries
GET    /health
```

`GET` one entry: `200` with the JSON body below, or `404`.

`PUT` body, last-write-wins:

```json
{ "kind": "testId", "value": "signin-btn", "origin": "LoginPage.java:12", "framework": "SELENIUM" }
```

`kind` and `value` are required and non-blank. `origin` may be empty. `framework` is optional. The server assigns `learnedAt` from its own clock and returns `200` with the stored entry. Clients do not send `learnedAt`.

Stored and returned entry:

```json
{
  "kind": "testId",
  "value": "signin-btn",
  "origin": "LoginPage.java:12",
  "framework": "SELENIUM",
  "learnedAt": "2026-10-02T16:40:00.000Z"
}
```

`GET` the collection returns a JSON object whose keys are locator keys and whose values are stored entries. This is the waiting-to-fix list.

`DELETE` is conditional:

- No entry: `204`.
- Stored `learnedAt` is later than `ifLearnedAt`: `409`, entry kept.
- Stored `learnedAt` is earlier or equal: `204`, entry removed.
- Missing or unparseable `ifLearnedAt`: `400`.

The compare-and-delete runs in one Redis Lua script so two shards cannot interleave a read and a delete.

`GET /health` pings Redis. `200` if the ping succeeds, `503` if it does not. It does not require a token.

If `GRAFT_CACHE_TOKEN` is set on the server, every `/v1` request without a matching bearer token gets `401`.

Other errors: unknown path `404`, bad namespace `400`, bad JSON `400`.

## Redis

Keys: `graft:{namespace}:{locatorKey}`. The value is the stored-entry JSON.

`learn` is `SET`. `get` is `GET`. `snapshot` is `SCAN` on the prefix `graft:{namespace}:`. `forget` is the Lua script above.

Dependency: `redis.clients:jedis:5.2.0`, `compileOnly`, same idea as Selenium. The published library does not force Jedis onto a project that only uses `HealingBy`. Running the server requires Jedis on the classpath:

```
java -cp graft.jar:jedis.jar tech.ishabbi.graft.cache.CacheServer
```

`CacheServer` is the only type that references `RedisLocatorStore`. `main` loads that class reflectively and, if Jedis is absent, exits with a message that names `redis.clients:jedis:5.2.0`. Loading `HealingBy` does not load Jedis.

| Env (server) | Meaning |
|---|---|
| `REDIS_URL` | Required. Example: `redis://127.0.0.1:6379` |
| `GRAFT_CACHE_PORT` | Listen port. Default `8741` |
| `GRAFT_CACHE_TOKEN` | Optional. If unset, `/v1` is open |

## Failure behavior in the test JVM

A cache outage is a miss, not a test failure.

- `get` throws or returns a non-2xx other than `404`: log a warning, behave as empty, continue to Alumnium.
- `learn` fails: log a warning. The heal already succeeded; the suggestion is simply not remembered.
- `forget` fails: log a warning. The current resolution continues.

`404` on `get` is an ordinary miss, not a warning.

## Healer changes

`AbstractHealer` already calls `get`, then `forget` on a learned miss, then `learn` after Alumnium. `LearnedLocators.get` keeps returning `Optional<LocatorSuggestion>` so existing tests stay valid. The facade adds `Optional<Entry> entry(String key)`, and `forget(String key, String learnedAt)` passes the `learnedAt` from that entry. `FileLocatorStore.forget` ignores the timestamp and deletes, matching today's single-file behavior. `HttpLocatorStore` sends it as `ifLearnedAt`. A `409` is a successful no-op (the newer row stays); the client does not warn and does not throw.

## Tests

Existing `LearnedLocatorsTest` passes unchanged.

New tests, no Docker and no Redis required for `gradle test`:

- `FileLocatorStore` round-trip, including `forget` clearing the file.
- `CacheServer` bound to an ephemeral port, backed by an in-memory `LearnedLocatorStore` (not Redis): put, get, snapshot, conditional delete (`409` when `ifLearnedAt` is older than the stored row, `204` when it is equal or newer), `401` when a token is configured, `400` on a bad namespace.
- `HttpLocatorStore` against that server: learn in one client, get in another; a refused connection is an empty get and does not throw.
- The compare-and-delete decision (newer row kept, older or equal row removed) is a pure function covered without Redis.

`RedisLocatorStore` against a real Redis runs only when `REDIS_URL` is set (`EnabledIfEnvironmentVariable`). The default test task does not start Redis.

## README

Document the env vars, the `java -cp` command, and that the file remains the default. State that the heal report is still local.

## Out of scope

- Hosting `heal-report.json`.
- A second Maven artifact.
- MySQL, Mongo, Dynamo, or a generic store plugin API beyond `LearnedLocatorStore`.
- TTLs, hit counts, and an admin UI.
- Authentication other than one optional bearer token.
