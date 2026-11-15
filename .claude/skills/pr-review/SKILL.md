---
name: pr-review
description: Use when asked to review a CloudStream pull request (by number, URL or branch), whether it is the user's own PR or someone else's. Produces an evidence-backed, severity-ranked report against the repo rules, with a security pass, a logging check, a test audit and manual device checks, saved uncommitted to plans/review-<PR>.md. Report only; it never changes code, pushes or posts.
---

# PR Review

A critical review of an open pull request before it merges. **Report first, with evidence. Change nothing.** The user decides what gets fixed.

## Hard rules

- **Read-only.** Don't edit code, commit, push, comment on GitHub or approve or request changes. The one file you write is the report, and it stays uncommitted. If the user later asks for fixes or a GitHub review, that's a separate step.
- **Every finding needs proof:** a `file:line` reference plus one of these:
  - a failing test or command run, with its output;
  - a measured cost (calls made, work done on the main thread, allocations in a hot loop);
  - a concrete reproduction with exact inputs, state and the result.
- **What you can't prove goes under Verify**, with what would prove it (the test, command or manual step). It is never stated as a finding.
- **No theoretical problems.** "Could be an issue if…" without a reproduction is not a finding. Zero findings is a valid answer.
- **Rank findings by severity, and give each a fix size.**
- **Flag only real problems:** security holes, broken or missing logs, needless abstraction, duplication, unclear names, misplaced logic, rule violations, bugs and inefficiency. No formatting nits.

## Workflow

### 1. Set up an isolated checkout
- Read the PR: number, title, body, head and base branches, author, files, commits and check status. Use `gh pr view <PR> --json number,title,body,headRefName,baseRefName,author,files,commits,statusCheckRollup` where `gh` works; in a Claude Code cloud session use the GitHub MCP tools (`pull_request_read` with `get`, `get_files`, `get_status`) instead.
- Check out the PR in a **fresh local clone**, never in the user's main checkout: `git fetch origin <headRefName> <baseRefName>` first, then `git clone --shared <main checkout> <path>` and, in the clone, `git fetch <main checkout> 'refs/remotes/origin/<headRefName>:refs/remotes/origin/<headRefName>' 'refs/remotes/origin/<baseRefName>:refs/remotes/origin/<baseRefName>'` and `git checkout --detach origin/<headRefName>` (fetching the base too lets the diff below run there). Don't use `git worktree`: the app's `generateGitHash` task reads `../.git/HEAD`, which doesn't exist in a worktree, so `assemblePrereleaseDebug` fails there. Copy `local.properties` into the clone (it holds `sdk.dir`).
- Diff against the merge base: `git diff origin/<base>...origin/<head>`. This is a fork: PRs normally target `master`; check `baseRefName`.
- Note the CI state. A failing or pending check is context, not a finding. Only report it if you can tie it to the diff.

### 2. Understand the intent
- Read the PR body and any linked GitHub issue, plus any investigation doc the PR adds (`docs/investigations/*.md`). Write down what the change is supposed to do.
- Spec gaps (asked for but missing, or built but not asked for) are findings, cited against the issue, PR body or the user's request.

### 3. Review the code against the repo rules
Read and apply `CLAUDE.md` (if present), `AI-POLICY.md` and `COMPOSE.md`. Give special weight to:
- **Simplicity and readability:** needless abstraction, one-line wrappers, vague names (`process`, `data`, `handle`), workflows that are hard to follow, logic in the wrong layer (UI code doing data work, view models touching Views).
- **Architecture rules from `COMPOSE.md`:** new Compose/shared code follows MVI (one immutable state, one sealed event type); code in `shared`/`library` common source sets uses KMP-compatible libraries only, no Java-only APIs.
- **Efficiency:**
  - work on the main thread: network, disk, JSON parsing or `getKey`/`setKey` in tight loops on `Dispatchers.Main`; use the repo's `ioSafe`/`main` helpers correctly;
  - hot paths: the player (`GeneratorPlayer`, link loading and switching), home/search result lists, RecyclerView adapters (diffing, rebinding), download workers, sync workers;
  - extension/provider calls: unbounded parallel calls, calls repeated per item, missing cancellation when the screen or episode changes, retries that can loop;
  - unbounded growth: lists, caches or saved preference values that grow forever;
  - prove a cost by measuring it, e.g. a throwaway JUnit test that counts calls on a fake, or timing a loop. Remove that scratch test afterwards or leave it untracked.
- **Lifetime and state:** Kotlin `object`s, companion objects and singletons outlive Activities and Fragments. Flag leaked `Context`/`View` references, per-screen state kept globally, state lost on configuration change or process death, and observers that are never removed.
- **Repo invariants:**
  - **Extension compatibility:** `library` is the API that installed extensions compile against. Public API changes must pass `./gradlew library:checkKotlinAbi` (or carry the repo's `@Prerelease` annotation and an updated `library/api` dump). Removing or changing a public signature breaks extensions already installed by users.
  - **Saved data compatibility:** preference keys (`getKey`/`setKey`, DataStore), backup/restore and cloud sync JSON must still read data written by the previous release; new fields need defaults; renamed keys need a migration or a fallback read.
  - **Upstream sync:** this repo is a fork that regularly syncs `recloudstream/cloudstream`. Prefer changes that keep upstream files easy to merge (new files or small hooks over large rewrites of upstream code); flag needless churn in upstream files.
  - Coroutines: no `GlobalScope` for screen work; exceptions in launched jobs are handled (`safe`, `safeApiCall`, `logError`), not swallowed silently.
  - Strings shown to users come from string resources, not hard-coded text.

### 4. Security pass
Do this for every PR, and in depth when the diff touches accounts, sync, credentials, network, WebViews, downloads, file access, plugin/extension loading, intents or deep links. Prove each finding with a test or a reproduction that shows the hole.

- **Credentials and accounts:** tokens and passwords (sync providers, OpenSubtitles, trackers) are not logged, not sent to the wrong host (redirects that forward `Authorization`), not exported in plain backups unless intended, and not shown in error messages.
- **Network:** no new cleartext HTTP where HTTPS is expected; TLS validation not disabled; redirects handled deliberately; responses validated before they overwrite saved data.
- **Untrusted input:** provider/extension responses, sync files, backups and deep-link or intent extras are untrusted. Check parsing failures, path traversal in file names, oversized payloads and injection into WebViews (JavaScript interfaces, `evaluateJavascript`).
- **Android components:** exported activities, receivers and providers; intent filters and deep links that can trigger actions without the user.
- **Secrets:** nothing committed (keys, keystores, tokens in code or `gradle.properties`).

### 5. Logging and diagnosability
Users report bugs with logcat output and in-app error messages; changes must keep problems diagnosable.

- **Failures are visible.** A new failure path logs (`logError`, `Log.*` with a clear tag) and, where the user is affected, shows a clear message or status. A swallowed exception counts as a missing log.
- **Existing messages keep their meaning.** Status texts and error strings that users or the issue tracker rely on aren't silently repurposed.
- **Volume stays proportional.** No log line per item, per chunk or per frame in loops, the player or list binding.
- **Logs leak nothing.** No passwords, tokens, auth headers or full URLs that carry credentials. This overlaps the security pass, so report it once.

Logging findings go in Fix with the usual proof, usually 🟡 Medium.

### 6. Audit the tests
Unit tests are JUnit in `app/src/test`, `library/src/commonTest` and `library/src/jvmTest`; instrumented tests are in `app/src/androidTest` (they need an emulator; CI runs them in `instrumented-tests.yml`). Run the tests the PR touches, and the suites around them:

- `./gradlew --no-daemon --max-workers=2 testPrereleaseDebugUnitTest` (app), `./gradlew --no-daemon library:jvmTest` (library), add `--tests '<Class>'` to target one class.
- Build: `./gradlew --no-daemon --max-workers=2 assemblePrereleaseDebug`. Run `library:checkKotlinAbi` when the diff touches `library`.
- **Environment:** the build needs a JDK 17 toolchain and the Android SDK (`sdk.dir` in `local.properties`). Maven Central can answer 429 to shared cloud IPs; wait a minute and retry rather than reporting it. No emulator is available in cloud sessions, so `androidTest` can't run there; say so under Manual checks instead.

1. **Coverage gaps:** for every behaviour the PR changes, find the test that would fail if it broke. Check this by reasoning about the assertion; where it's cheap, break the code temporarily in the throwaway clone and run the test. **Report only the behaviours with no such test.** Leave covered behaviours out of the report.
2. **Missing edge cases.** At least consider:
   - data written by the previous release (old preference values, old sync/backup JSON);
   - empty, null, huge, unicode and odd-cased input (titles, source names, URLs);
   - network failures: timeouts, redirects, HTML error pages, partial bodies, 4xx/5xx;
   - concurrency: two devices syncing, a screen closed mid-load, an episode switched mid-request, retries;
   - extensions that fill fields inconsistently (missing quality, `source == name`, duplicate names);
   - TV/landscape and phone layouts where the UI changed.
3. **Tests that prove nothing:**
   - tests that can't fail;
   - tests that only assert "no exception";
   - tests that re-test Kotlin, Android or a library;
   - tests that mock away the code under test;
   - tests that duplicate another test.
4. **Misplaced or polluting tests:**
   - unit tests that need a device or a real network call (move to `androidTest` or use a fake server);
   - `Thread.sleep`, wall-clock time or shared global state between tests;
   - slow tests (give the timing).
5. **What tests can't prove:** manual device steps, but **only for user-visible behaviour that no test pins** (rendered UI, the player, real provider or sync-server calls, TV navigation). Each step gives the screen, the setup, what to do, and the expected result. Usually there are 3–5 steps; don't re-check behaviour a test already proves.

### 7. Write the report
Save it to `plans/review-<PR number>.md`, and **don't commit it**.

**The report contains only what the reader must act on or decide.** You did all the checking above; the report doesn't narrate it.

Leave out:
- anything checked and found fine: covered behaviours, rules followed, spec items done, "none found", things done right;
- run logs and test counts. A green suite isn't a finding; quote output only as proof inside a finding;
- CI and branch status, the author, and "nothing was posted";
- environment notes about your own setup (SDK paths, JDK, retries);
- items you decided not to flag, and anything already labelled "not a bug", "matches the request" or "isn't a finding";
- background the finding doesn't need.

#### Structure: group by what the reader has to do
Use these sections, in this order. Sort each one most urgent first. **Drop any section that has nothing in it**, and don't write "None".

1. **Verdict line:** the call (OK to merge / merge after fixes / rework), then counts per section, then the reviewed short sha. Someone reading only this line should know how much work is waiting.
2. **Fix:** code problems, as a table sorted by severity. Security holes and spec deviations that need a code change go here.
3. **Decide:** questions only a person can answer (product behaviour, a policy call). These aren't bugs, so they don't go in Fix.
4. **Verify:** suspicions you couldn't prove (the "unproven" items), each with the one run or check that settles it.
5. **Add tests:** changed behaviour that no test would catch, each with the exact test to write.
6. **Manual checks:** steps for behaviour no test proves.
7. **Proof:** a collapsed `<details>` block at the bottom with the evidence behind each Fix row (output, measurements, quotes). It's there for anyone who doubts a finding, and out of the way otherwise.

#### Writing style
Write the way you'd brief a teammate, not as a log.
- **Fix rows are short but readable:** "A redirected upload is reported as a successful sync", not "missing redirect guard".
- **Decide items:**
  - lead with the question in plain words, as a bolded sentence ending in "?";
  - then a sentence or two on why it's unclear, naming the sources that disagree;
  - then who can answer it, and your recommendation, so the reader can just agree or disagree;
  - say what changes for each answer when it isn't obvious, for example "if it's out, that's a one-line change (Fix #1)".
- **Verify and Add tests items:** say what you suspect and what would settle it, in one sentence each.
- **Leave out:**
  - terse arrows (`→ product`) and semicolon-chained fragments in prose;
  - internal shorthand nobody else would recognise.

  Arrows are fine in manual steps (action → expected result).
- **Budget: about one screen (~50 lines) before the Proof block.** Cut words, not findings.

**Severity:**

| Mark | Severity | Means |
|---|---|---|
| 🔴 | Critical | data loss (watch history, library, sync data), a security hole, a crash on a common path, or broken extensions |
| 🟠 | High | wrong behaviour users will hit |
| 🟡 | Medium | a rule violation or inefficiency with a real cost |
| 🟢 | Low | readability or maintainability |

**Fix size:** XS is under 10 lines; S is one function or test; M is a few files; L is a redesign.

#### Template

~~~markdown
# Review #<PR>: <title>
**Verdict:** <OK to merge / merge after fixes / rework> · <n> blockers · <n> fixes · <n> decisions · <n> to verify · <n> tests to add · `<short sha>`

## Fix
| # | Sev | Size | Problem | Where | Fix |
|---|---|---|---|---|---|
| 1 | 🟡 Med | XS | <readable one-liner> | `path:line` | <one-line fix> |

## Decide
- **D1. <The question, in plain words?>** <Why it's unclear: which sources disagree.> <Who can answer, and your recommendation.> <What changes for each answer, if not obvious.>

## Verify
- **V1. <What you suspect.>** <The one run or check that settles it> (`path:line`).

## Add tests
- **T1. <Behaviour that no test catches.>** <The test to write> (`path:line`).

## Manual checks
1. <Screen> with <setup>: <steps> → <expected>.

<details><summary>Proof</summary>

**#1** <evidence: output, measurement or quote>
</details>
~~~

### 8. Hand back
Reply with the verdict line, the Fix table, the Decide questions, and the report path. The reply follows the same leave-out rules as the report. Then stop and let the user choose what to fix. Delete the review clone when you are done, if it was yours and holds no work worth keeping.
