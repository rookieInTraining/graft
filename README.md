# Graft

Self-healing locators for Selenium, Appium, Playwright and Maestro, with [Alumnium](https://alumnium.ai)
as the donor. Keep your locators; add a description; when a locator breaks, Alumnium finds the
element from the description and is grafted in place of it — the test keeps going, the graft is
remembered for the next run, and a report tells you what to fix in source.

```java
public class LoginPage {
    @Element("the 'Sign in' button below the password field")
    @FindBy(id = "login-submit")                       // existing locator, untouched
    WebElement signIn;

    @Element(value = "the email field on the login form", css = "input[name=email]")
    WebElement email;

    @Element("the cookie consent 'Accept all' button")  // no locator: always AI-resolved
    WebElement acceptCookies;
}

Healer healer = Graft.with(driver);                      // WebDriver | Page | MaestroDevice
LoginPage page = Graft.page(LoginPage.class, healer, driver);
page.signIn.click();                                    // heals on NoSuchElementException

// ...or as plain locator constants, no page factory, no driver in scope:
static final By SIGN_IN = Graft.by(By.id("login-submit"), "the 'Sign in' button");
static final By BANNER  = Graft.describe("the cookie consent banner");
wait.until(elementToBeClickable(SIGN_IN)).click();
```

## Why this shape

Alumnium already has the primitive: `al.find("description")` returns a **native** element
(`WebElement` for Selenium/Appium, `Locator` for Playwright). Everything here is policy around
that one call — when to invoke it, how often, and what to tell the engineer afterwards.

Design rules:

1. **Locator first, always.** The declared locator gets a fair chance (`locatorTimeout`,
   default 5 s). Healing is for broken selectors, not for timing. Never heal on success.
2. **The proxy is the real thing.** Fields are JDK proxies implementing `WebElement`/`Locator`,
   so Playwright assertions, Selenium `Actions`, waits, and `WrapsElement` all work.
3. **Heals are bounded and visible.** A per-element budget caps LLM spend; every heal is
   logged, fanned out to listeners, and written to `.graft/heal-report.json` with a
   `suggestedLocator` you can paste back into the page object.
4. **Nothing is forced on the project.** Framework jars are `compileOnly`; the framework
   adapters load only when used; the `Alumni` instance is created on the first heal (or you
   pass in the one you already use for `act/check/get`).

## Three ways to declare a healable locator

| Style | Selenium / Appium | Playwright | Maestro |
|-------|-------------------|------------|---------|
| Field annotation | `@Element(...) WebElement f` | `@Element(...) Locator f` | `@Element(...) MaestroElement f` |
| Constant, locator-first | `Graft.by(By.id("x"), "desc")` → `HealingBy` | `Graft.selector("#x", "desc")` / `PlaywrightHealer.selector(p -> p.getByLabel("Email"), "desc")` | `Graft.selector(MaestroSelector.id("x"), "desc")` |
| Constant, description-only | `Graft.describe("desc")` → `AlumniumBy` | `Graft.selector(null, "desc")` | `Graft.selector(null, "desc")` |

All three feed the same `LocatorSpec` → `AbstractHealer.heal(...)` pipeline, so budgets, learned
locators, the report and strict mode behave identically.

### `HealingBy` / `AlumniumBy` (Selenium, Appium)

`By` is abstract with `findElement(SearchContext)` / `findElements(SearchContext)` — the same
extension point `ByAll` and `ByChained` use. Neither class implements `By.Remotable`, so
`RemoteWebDriver` always routes through them instead of sending the locator to the wire.

- `HealingBy.findElement`: primary polled for the timeout → learned locator → Alumnium. The healed
  element is cached for the session, so `WebDriverWait` polling doesn't re-pay the timeout.
- `HealingBy.findElements`: primary only, never heals. An empty list is a legitimate answer.
- `parent.findElement(healingBy)`: the healed element must be inside `parent` (DOM containment via
  JS on the web, rect containment on native mobile) or the original `NoSuchElementException` surfaces.
- Returns the raw `WebElement`; `.proxied()` returns a self-healing proxy with stale-reference recovery.
- `AlumniumBy` is the primitive: description → `Alumni.find` on every call, no policy. It also
  composes with stock Selenium: `new ByAll(By.id("login"), AlumniumBy.describe("..."))`.

The healer is found through `DriverRegistry`, keyed by **WebDriver session id** (then driver
identity, then the thread's most recent healer). `Graft.with(driver)` registers; `healer.close()`
unregisters. Elements and decorated drivers are unwrapped via `WrapsDriver`/`WrapsElement`.

### Learned locators

After a heal, the suggestion derived from the found element is remembered under the locator's key.
Next time: primary → **learned** → Alumnium. A broken locator costs one LLM call per suite instead
of per run. The store is also the list of locators still waiting for a fix in source. An entry is
removed when that learned locator stops matching.

A learned locator keeps the context it was found in. When the element sits in an iframe or an open
shadow root, the entry carries the `within` hops (see below) and replay re-enters them, so a heal
learned inside a same- or cross-origin iframe replays there. Entries without `within` behave as
before.

**Rollout:** upgrade `CacheServer` before the test clients. An old server drops `within` on `PUT`,
and an old client replays a `within` entry in the current context, where it either misses (the entry
is then evicted) or hits the wrong element.

The default store is `.graft/learned-locators.json` in the process working directory, written on
each heal. `GRAFT_LEARNED` moves the file. `GRAFT_LEARNED=none` turns the file off. The heal
report (`.graft/heal-report.json`) is a different file, still local, written when the JVM exits.

To share heals across machines and CI shards, run Redis and `CacheServer`, then point the tests
at the server. The test JVM speaks HTTP only. Jedis is `compileOnly` (`redis.clients:jedis:5.2.0`)
and is loaded by the server, so a project that only uses `HealingBy` does not pull Redis onto its
classpath.

```bash
docker run -d --name graft-redis -p 6379:6379 redis:7
gradle cacheServer
```

`gradle cacheServer` puts Jedis on the classpath and listens on port `8741`. `REDIS_URL` defaults
to `redis://127.0.0.1:6379`. `GRAFT_CACHE_PORT` defaults to `8741`. `GRAFT_CACHE_TOKEN`, when set,
requires `Authorization: Bearer` on `/v1`. `GET /health` pings Redis and does not require the token.

On the test JVM:

```
GRAFT_LEARNED_URL=http://127.0.0.1:8741
GRAFT_NAMESPACE=default
GRAFT_CACHE_TOKEN=
GRAFT_LEARNED_TIMEOUT_MS=500
```

`GRAFT_NAMESPACE` matches `[A-Za-z0-9._-]{1,64}` and defaults to `default`. `GRAFT_LEARNED_URL`
wins over the file, including when `GRAFT_LEARNED=none`. If the cache is down, Graft logs a
warning, treats the lookup as a miss, and asks Alumnium. A delete does not remove a row that was
learned later than the timestamp that client observed.

Redis keys are `graft:{namespace}:{locatorKey}`.

| Method | Path | Result |
|--------|------|--------|
| `GET` | `/v1/namespaces/{ns}/entries/{key}` | `200` entry, or `404` |
| `PUT` | `/v1/namespaces/{ns}/entries/{key}` | stores `{kind, value, within?, origin, framework}`; `within` is an optional string array; server sets `learnedAt` |
| `DELETE` | `/v1/namespaces/{ns}/entries/{key}?ifLearnedAt={ts}` | `204` removed, `409` a newer row was kept, `400` missing timestamp |
| `GET` | `/v1/namespaces/{ns}/entries` | every entry in the namespace |
| `GET` | `/health` | `200` if Redis answers, `503` if it does not |

## Selenium Grid and remote devices

The wrapper never assumes a local driver:

| Concern | How it's handled |
|---------|------------------|
| Driver type | Only `WebDriver`/`SearchContext` APIs; `RemoteWebDriver`, Appium drivers, `Augmenter`ed and `EventFiringDecorator`-wrapped drivers all work. |
| Which framework? | Detected from **capabilities** (`platformName` ios/android, `appium:automationName`), not the class — a plain `RemoteWebDriver` hitting an Appium node through a grid relay gets Appium locator mapping and suggestions. |
| Many sessions per JVM | Registry keyed by session id; one healer, one healed-element cache per session. |
| Parallel tests | Healers are per driver. The default learned store is one JSON file per runner; sharded runs each learn independently and the files merge by key. `GRAFT_LEARNED_URL` shares one Redis-backed cache instead, and a newer heal is not deleted by an older miss. |
| Element identity | Alumnium attaches to the same session, so every healed element belongs to that session — nothing crosses sessions or nodes. |
| Where Alumnium runs | On the test runner, next to the tests — never on a grid node. |
| Playwright remote | `connect()`, `connectOverCDP()` or Chromium via Selenium Grid (`SELENIUM_REMOTE_URL`): the `Page` is the same object; healers are registered per `Page`. |
| Maestro remote | Pass the `adb connect host:port` serial / simulator UDID as `device` — Maestro and Alumnium's session get the same identifier. `MaestroDevice.Builder.remote(host, port)` forwards `--host/--port` for farms exposing the Maestro driver over the network. |

One thing to verify in your topology rather than in this code: Alumnium's own Selenium driver must
be able to reach the session through the hub (it needs the WebDriver session plus Chromium's CDP
vendor endpoint on the node). The wrapper adds no further requirement.

## Architecture

```
                 @Element field                      Graft.with(driver | page | device)
                       │                                          │
                       ▼                                          ▼
              HealingPageFactory ──createElement(spec)──▶ Healer (per framework)
                       │                                          │
                       ▼                                          ▼
        proxy: WebElement / Locator / MaestroElement     AbstractHealer.heal(...)
        or: HealingBy / AlumniumBy / HealingSelector     enable? learned? budget? time it,
                       │                                 suggest, learn, notify,
         every call ───┤                                 strict → fail on purpose
                       │
            ┌──────────┼──────────────────┐
            ▼          ▼                  ▼
   1. primary     2. learned        3. on NoSuchElement /      ──▶ HealListener(s)
      (polled)       locator           TimeoutError(count==0) / ──▶ HealReport (JSON)
                     (one try)         Maestro "Element not found" ──▶ LearnedLocators (file or HTTP cache)
                                              │
                                              ▼
                             Alumni.find(description)        (Selenium / Appium / Playwright)
                             Alumnium MCP  do(description)   (Maestro — see below)
```

| Framework   | Field type       | Primary path                                   | Heal trigger                                        | Heal result                       |
|-------------|------------------|------------------------------------------------|-----------------------------------------------------|-----------------------------------|
| Selenium    | `WebElement`     | `@Element` locator or `@FindBy/@FindBys/@FindAll` | `NoSuchElementException`, `InvalidSelectorException` | `Alumni.find` → `WebElement`      |
| Appium      | `WebElement`     | + `@AndroidFindBy`/`@iOSXCUITFindBy`, `AppiumBy` kinds | same                                           | `Alumni.find` → `WebElement`      |
| Playwright  | `Locator`        | `@Element` locator → `page.locator/getByTestId/getByText`; with `within`, the same calls on the scope (`frameLocator(css)` / host `locator(css)`) | `TimeoutError` on attach-wait, or on an action **when the locator matches 0 nodes** | `Alumni.find` → `Locator` |
| Maestro     | `MaestroElement` | one-command flow via `maestro test`            | output contains "Element not found"                 | Alumnium MCP `do("<action> the <description>")` |

### Locator attributes on `@Element`

Set at most one. Without one, the field falls back to the framework's own annotations; with
neither, the element is description-only (Alumnium is the primary locator).

| Attribute            | Selenium                       | Appium                                   | Playwright          | Maestro |
|----------------------|--------------------------------|------------------------------------------|---------------------|---------|
| `id`                 | `By.id`                        | `AppiumBy.id` (resource-id)              | `[id="…"]`          | `id:`   |
| `css` / `selector`   | `By.cssSelector`               | `By.cssSelector` (webviews)              | `page.locator(…)`   | —       |
| `xpath`              | `By.xpath`                     | `By.xpath`                               | `xpath=…`           | —       |
| `name`               | `By.name`                      | —                                        | `[name="…"]`        | —       |
| `text`               | xpath on `text()`              | xpath on `@text/@label/@name`            | `getByText(exact)`  | `text:` |
| `testId`             | `[data-testid="…"]`            | Android resource-id / iOS accessibility id | `getByTestId`     | `id:`   |
| `accessibilityId`    | —                              | `AppiumBy.accessibilityId`               | —                   | `id:`   |
| `androidUIAutomator` | —                              | `AppiumBy.androidUIAutomator`            | —                   | —       |
| `iosClassChain`      | —                              | `AppiumBy.iOSClassChain`                 | —                   | —       |
| `iosPredicate`       | —                              | `AppiumBy.iOSNsPredicateString`          | —                   | —       |
| `within`             | frame / shadow chain           | —                                        | frame / shadow chain | —      |

`within` is a modifier, not a locator: it goes next to one of the attributes above.

Per-element overrides: `heal = false` (fail fast), `timeoutMs = 500` (shorter/longer locator wait).

#### Locators inside iframes and shadow roots (`within`)

```java
@Element(value = "the card number field", within = {"frame=#checkout", "shadow=pay-widget"}, css = "input.card")
WebElement cardNumber;

static final By PAY = HealingBy.of(By.cssSelector("button.pay"), "the Pay button").within("frame=#checkout").proxied();
static final HealingSelector CARD = HealingSelector.of("input.card", "the card number").within("frame=#checkout");
```

- Hops are outside-in. `frame=<css>` enters the iframe matched by `<css>`; `shadow=<css>` enters the
  open shadow root of the host matched by `<css>`. Each hop's CSS is resolved in the scope left by
  the previous one. Everything after the first `=` is the CSS, so `frame=iframe[name='pay']` is fine.
- An empty `within` is today's behaviour: no switching, the current search context.
- A non-empty `within` resolves from the top-level document when the search context is the driver or
  the page, and relative to the element or shadow root when the search context is a scoped one
  (`parent.findElement(by)`).
- Selenium: `HealingBy.of(...).within(...)`. Playwright: `HealingSelector.of(...).within(...)`.
- `within` is part of the locator's key, so the same selector in two contexts learns separately.

Restrictions, all rejected with `IllegalArgumentException`:

- `within` needs an inline `@Element` locator attribute. `@FindBy` plus `within` is rejected in v1,
  and so is `within` with no locator.
- A description-only `HealingSelector` with `within` is rejected: Alumnium finds it wherever it is.
- Selenium supports only CSS inside a shadow root. When the chain ends in a shadow hop, `xpath` and
  `text` locators are rejected. Playwright rejects `xpath` after any shadow hop.

## Heal policy (`HealingConfig`)

| Knob                 | Default                       | Env override                 |
|----------------------|-------------------------------|------------------------------|
| `enabled`            | `true`                        | `GRAFT_ENABLED=false`        |
| `strict`             | `false`                       | `GRAFT_STRICT=true`  |
| `locatorTimeout`     | 5 s                           | `GRAFT_TIMEOUT_MS`   |
| `pollInterval`       | 250 ms                        | —                            |
| `maxHealsPerElement` | 5 per session                 | —                            |
| `reportPath`         | `.graft/heal-report.json`  | `GRAFT_REPORT` (`none` disables) |
| `learnedLocatorsPath`| `.graft/learned-locators.json` | `GRAFT_LEARNED` (`none` disables the file) |
| `learnedUrl`         | unset (use the file)          | `GRAFT_LEARNED_URL` (wins over the file) |
| `namespace`          | `default`                     | `GRAFT_NAMESPACE` |
| `cacheToken`         | unset                         | `GRAFT_CACHE_TOKEN` |
| `learnedTimeout`     | 500 ms                        | `GRAFT_LEARNED_TIMEOUT_MS` |
| `listeners`          | `HealReport.global()`         | —                            |

**Strict mode** is the CI lever: the element is still healed (so the report gets a suggestion),
then the step fails with `HealingException` carrying the `HealEvent`. Run PR pipelines strict and
nightly lenient and locators get fixed instead of rotting behind the AI.

**After a heal** the broken locator is not re-waited on every interaction (that would add the full
timeout to each click). The healed element is cached per session; a `StaleElementReferenceException`
re-resolves (quick retry of the primary, then Alumnium again, counting against the budget).
`healer.invalidate(spec)` / `invalidateAll()` reset after navigation if you need to.

### Report

`.graft/heal-report.json`, written on JVM exit:

```json
{
  "healed": [{
    "framework": "SELENIUM",
    "element": "com.acme.LoginPage#signIn",
    "description": "the 'Sign in' button below the password field",
    "origin": "com.acme.LoginPage#signIn",
    "originalLocator": "By.id: login-submit",
    "failureReason": "NoSuchElementException: no such element: Unable to locate element: {\"method\":\"css selector\",\"selector\":\"#login-submit\"}",
    "healedTo": "<button> \"Sign in\"",
    "suggestedLocator": "@Element(testId = \"login-submit-btn\")",
    "suggestionKind": "testId",
    "suggestionValue": "login-submit-btn",
    "suggestionWithin": ["frame=#login"],
    "durationMs": 1840,
    "at": "2026-10-01T09:12:44.102Z"
  }],
  "unhealed": [],
  "totals": { "healed": 1, "unhealed": 0 }
}
```

`suggestionWithin` is written only when the suggestion has hops (the element sits in an iframe or an
open shadow root); the `suggestedLocator` annotation then carries the matching `within = {...}`.

Suggestions come from one in-page script shared by Selenium and Playwright. The preference order is
what survives UI churn best, and every candidate is verified to match exactly this element. On the
web:

1. `data-testid`
2. `data-test`
3. `id`
4. `name`
5. `aria-label`
6. a path from the nearest ancestor that has one of those attributes
7. the element's own visible text, up to 60 characters
8. a structural path from the document root

Generated-looking ids and values (React/Ember/MUI ids, long digit runs) are skipped, and `class` is
never used. Playwright's uniqueness check counts open shadow roots, matching how its locators pierce
them. On native mobile (Appium) the order is `resource-id`/`content-desc` (Android) or `name` (iOS),
then an XPath anchored on the nearest ancestor with one of those, then `text`/`label`; each is checked
against the page source.

Hook your own sink with `HealingConfig.builder().addListener(...)` (Allure step, ReportPortal,
a bot that opens a locator-fix PR).

## Maestro specifics

Alumnium's Maestro driver lives only in the MCP server (`ALUMNIUM_DRIVER=maestro`), and the
agentic MCP toolset is `start/stop/do/check/get/wait` — there is no `find`. So the Maestro adapter:

- runs each `MaestroElement` action as a one-command flow (`maestro test`, no `launchApp`, so it
  acts on the current screen);
- on "Element not found", calls `do("tap on the <description>")` in an Alumnium Maestro session
  on the same device (both sides drive the device via the Maestro CLI, so they see the same
  screen);
- `assertVisible` heals through `check("the <description> is visible on screen")` — a passing
  check means the selector was wrong, a failing one is a genuine assertion failure;
- the suggestion comes from `get(...)` asking for the element's id/text.

`AlumniumMcpClient` is a ~300-line JSON-RPC-over-stdio client. It discovers tool argument names
from `tools/list` instead of hard-coding them, so server-side renames don't break it.

**Session timing matters:** Alumnium's `start` launches the app. `Graft.with(device)` starts the
session eagerly so a mid-test heal never relaunches the app; drop `launchApp` from your flows or
accept one extra launch. `MaestroHealer.lazy(...)` exists if you want to defer.

**Cost:** a JVM start per Maestro step (~2–3 s). Good enough for healing-first screen objects;
batching with resume-from-failed-step is the next item (below).

## Setup

```kotlin
repositories {
    mavenLocal()    // after `gradle publishToMavenLocal` in this repo
    mavenCentral()
}

dependencies {
    testImplementation("tech.ishabbi:graft:0.1.0-SNAPSHOT")
    testRuntimeOnly("ai.alumnium:alumnium-cli-linux-x64:0.23.0")   // the binary for your CI platform
}
```

`gradle cacheServer` in this repo starts that process and adds Jedis itself. Jedis is not a
transitive dependency of the published `graft` jar.

Configure an AI provider exactly as for Alumnium (`OPENAI_API_KEY`, `ALUMNIUM_MODEL=anthropic`,
Ollama, …). Alumnium's element cache applies to healed finds, so repeated runs with an unchanged
UI do not pay for the LLM again.

For Maestro: Maestro CLI on `PATH` (or `MAESTRO_BINARY`), Alumnium binary on `PATH`
(or `ALUMNIUM_BINARY`).

## Limitations (v0.1)

- `List<WebElement>` fields are not healed — Alumnium's `find` returns one element. Use `@FindBy`
  for lists for now.
- Selenium implicit waits add to each poll of the locator timeout; prefer explicit waits (as usual).
- Playwright heal-on-action only fires when the locator matches zero nodes at timeout time;
  "covered"/"disabled" timeouts surface unchanged by design.
- Maestro: one `maestro test` per step; `scrollUntilVisible`, `swipe`, etc. not wrapped yet.
- `HealingBy` keeps the raw-element contract, so stale-reference recovery needs `.proxied()`.
- A raw `HealingBy.findElement` of iframe content leaves the driver switched into that frame, because
  the element is only usable there. Use `.proxied()`: it enters the element's frame for each call and
  restores the test's frame afterwards. Children found through such a proxy
  (`proxy.findElement/findElements`) are frame-aware proxies too, not raw driver elements, so
  identity and `equals` against raw elements do not hold.
- Selenium finds a frame by walking the frame tree: at most 6 levels deep and 64 frames. If Graft
  cannot work out the iframe path of an element Alumnium healed (frame unreachable or caps
  exceeded), no frame hops are learned and the driver is left in the frame Alumnium switched to.
  During frame discovery Graft briefly stamps `data-graft-probe` / `data-graft-frame-<nonce>`
  attributes on the page; they are removed again, except when a frame cannot be reached afterwards.
- Closed shadow roots cannot be re-entered: the heal works, but nothing is learned.
- Appium webview contexts are not handled; native suggestions use the native page source only.
- Playwright `xpath` locators cannot be used under a shadow hop, and `@FindBy` with `within` is not
  supported (see `within` above).
- The default learned store is one JSON file per runner. Set `GRAFT_LEARNED_URL` to share heals
  through `CacheServer`. The heal report stays on the local disk.
- Real-browser tests (iframes, shadow roots, suggestions) run only with `GRAFT_BROWSER=true` and need
  Chrome for Selenium and Playwright's browser. `gradle test` without it is hermetic. The
  `GRAFT_E2E=true` examples additionally need an AI provider (and an emulator for Appium/Maestro).
- Not compiled against the real jars in the authoring sandbox (no Maven access there) — expect
  small signature fixes on first build, particularly around Appium's `DefaultElementByBuilder`
  and Playwright option classes.

## Roadmap

1. `HealingElementLocator`: wrap Selenium's/Appium's `ElementLocator` so `PageFactory.initElements`
   heals with a one-line change and the `@Element` proxy becomes an implementation detail.
2. Maestro batching: run the whole flow once, parse the failed command index from Maestro's
   output, heal that step, resume from the next.
3. Lists: `findAll`-style healing once Alumnium exposes it, or heal-to-singleton as an opt-in.
4. Reuse the Java client's server transport for Maestro instead of spawning `alumnium mcp`
   (the client already bundles and starts the same binary).
5. Auto-fix mode: a Gradle task that reads the report / learned store and rewrites locators as a
   reviewable diff.
6. Heal metrics (count, p95 heal latency, cost) as OpenTelemetry spans alongside Alumnium's
   own tracing (`ALUMNIUM_TRACE=true`).
