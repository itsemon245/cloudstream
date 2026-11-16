# Pull-request CI

`.github/workflows/pull_request.yml` ("Artifact Build") runs on every pull request and on
every push to `master`. It has two jobs that run at the same time:

| Job | Runs |
| --- | --- |
| Build and test | `:app:assemblePrereleaseDebug`, `:app:testPrereleaseDebugUnitTest`, `allTests` of `library`, `shared` and `desktopApp`, `:docs:test`, and `:library:checkKotlinAbi` when a pull request changes `library/` |
| Lint | `lint`: the app's prerelease debug variant (with its unit and instrumented test sources) and the Android and JVM targets of `library` and `shared` |

It used to run `assemblePrereleaseDebug lint check` in one job. `check` also compiled and
unit-tested the `stable` flavor. The two flavors share all their code (`prerelease` only adds
launcher icons, an application id suffix and a version code), so CI compiled the app twice.
In `library` and `shared`, `check` also runs lint, which the Lint job already does.

Only `prerelease` is built now. A test that only fails in `stable` would no longer be caught;
run `./gradlew :app:testStableDebugUnitTest` locally if you change flavor-specific code.

## Caching

[setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md) saves the
Gradle User Home (dependencies, transforms and the local build cache) in the GitHub Actions
cache, one entry per job.

- A run can only restore caches saved on its own ref or on the base branch. Pull requests
  can't see each other's caches, so before this workflow ran on `master` every pull
  request started cold.
- Only `master` saves (`cache-read-only` is true elsewhere). Pull-request entries would be
  useless to every other pull request and would push `master`'s entries out of the
  repository's 10 GB cache limit.
- The job names (`build-test`, `lint`) are unique across workflows. setup-gradle restores
  the newest entry saved by a job with the same id, and `build-apk.yml`'s job is called
  `build`, so reusing that name would restore a cache full of release-build outputs.

### What can't come from the cache

`app/build.gradle.kts` puts the time of configuration into `BuildConfig.BUILD_DATE`, and the
prerelease flavor's `versionCode` is the current minute. `BuildConfig` is an input of the app's
Kotlin compile, so `:app:compilePrereleaseDebugKotlin` and everything after it (dexing, the app's
unit tests, the app's lint analysis) re-run on every build, even when a pull request doesn't
touch the app. Library, shared, desktop and docs tasks do come from the cache.

Once the configuration cache is saved (below), a reused entry also keeps the time it was made,
so `BUILD_DATE` and the version code are those of the run that stored it, and app tasks can be
reused too.

### Configuration cache

Gradle's configuration cache can hold secrets, so setup-gradle only saves it when it has an
encryption key. Without one the log says `Not saving configuration-cache state, as no
encryption key was provided` and every run spends 30–40 s configuring the build before the
first task starts. A reused entry takes about 5 s.

The repository has a `GRADLE_ENCRYPTION_KEY` secret. To create or rotate it:

1. Generate a key: `openssl rand -base64 16`
2. Repository **Settings → Secrets and variables → Actions → New repository secret**, name
   `GRADLE_ENCRYPTION_KEY`, value the key. Or, with the GitHub CLI:
   `gh secret set GRADLE_ENCRYPTION_KEY --body "$(openssl rand -base64 16)"`

The workflow passes it to setup-gradle. `master` runs save the configuration cache, and pull
requests reuse it unless they change the build scripts, a Gradle property or file the build
reads while configuring (such as the version catalog), or the tasks a job runs. A new key only makes the existing entries unreadable once;
the next `master` run saves new ones.

## Measurements

GitHub-hosted `ubuntu-latest`, pull request #6. Times are from the start of the run to the end of the slowest job.

| Run | Wall clock | Build and test (Gradle step) | Lint (Gradle step) |
| --- | --- | --- | --- |
| Before: one job, `assemblePrereleaseDebug lint check` (PR #3) | 7 min 8 s | 6 min 29 s (both, one job) | |
| Cold: no cache for these jobs yet | 6 min 46 s | 5 min 4 s | 6 min 2 s |
| Warm cache, configuration cache missed | 4 min 25 s | 2 min 27 s | 3 min 49 s |
| Warm cache and configuration cache, nothing changed | 47 s | 19 s | 15 s |

A pull request that changes app code lands between the last two rows: the app's compile, tests
and lint analysis re-run (about 1.5–2.5 min each in parallel) and everything else comes from
`master`'s cache.

## Keeping it fast

- **Build scans**: `./gradlew <tasks> --scan` shows the task timeline and why tasks weren't
  taken from the cache. Scans go to scans.gradle.com, are free, and are **public** to anyone
  with the link. In CI, setup-gradle publishes one with `build-scan-publish: true`,
  `build-scan-terms-of-use-url: https://gradle.com/terms-of-service` and
  `build-scan-terms-of-use-agree: yes`.
- **Locally**: `./gradlew <tasks> --profile` writes an HTML report to `build/reports/profile/`.
  [Gradle Profiler](https://github.com/gradle/gradle-profiler) benchmarks a change
  repeatably (`--benchmark`).

## Faster tools: options and tradeoffs

Checked in November 2026. Prices change; check the linked pages.

### Remote build cache

The GitHub Actions cache already acts as this repository's build cache. A real remote cache
mainly helps when many branches build different code at once, or when developers want CI's
outputs locally.

- [Develocity](https://gradle.com/develocity/) (Gradle Inc.): remote cache, build scans,
  test distribution, predictive test selection. Commercial; free instances only for
  [selected open-source projects](https://gradle.com/enterprise-customers/oss-projects/),
  usually large foundations. Not realistic for a fork.
- [Build Cache Node](https://docs.gradle.com/build-cache-node) (`gradle/build-cache-node`
  Docker image): free to run on your own server without Develocity. You host and secure it,
  and pull requests from forks can't get write credentials.
- [burrunan/gradle-cache-action](https://github.com/burrunan/gradle-cache-action): exposes
  the GitHub Actions cache as a Gradle HTTP build cache, so caching is per task rather than
  one archive per job. Replaces setup-gradle's caching rather than adding to it; worth a try
  only if the job-level cache turns out to miss a lot.

### Faster runners

Public repositories get the standard 4 vCPU / 16 GB `ubuntu-latest` runner free. Every
option below costs money; each is a GitHub App install plus a `runs-on:` change, so lock-in
is low (their caches are their own).

| Option | Notes |
| --- | --- |
| [GitHub larger runners](https://docs.github.com/en/billing/reference/actions-runner-pricing) | 8 and 16 cores, billed per minute even on public repos; no free minutes. Simplest: no third party. |
| [Blacksmith](https://docs.blacksmith.sh/blacksmith-runners/pricing) | Free tier (3,000 2-vCPU minutes a month), then roughly half GitHub's per-minute price; fast local cache. `runs-on: blacksmith-4vcpu-ubuntu-2404`. |
| [Depot](https://depot.dev/products/github-actions), [Namespace](https://namespace.so/docs/solutions/github-actions), [WarpBuild](https://www.warpbuild.com/) | Similar per-minute managed runners with their own caches; trials, no OSS program found. |
| [Ubicloud](https://www.ubicloud.com/docs/github-actions-integration/quickstart) | Cheapest per minute, small monthly free credit, open-source platform. |
| [RunsOn](https://runs-on.com/) | Runners in your own AWS account (spot instances); you pay AWS plus a licence. Most setup. |
| BuildJet | Shut down in March 2026. |

The build is mostly CPU-bound Kotlin compilation and lint, so 8 vCPUs would cut the build
job noticeably; it isn't free, though, and the two jobs already split the work across two
free runners.

### Lint

- ktlint and detekt are style and static-analysis tools; they don't replace Android lint's
  API-level, resource and manifest checks.
- Lint already analyses each module separately (partial analysis, AGP 7+) and its
  `lintAnalyze*` tasks are cacheable, so unchanged modules come from the build cache once
  `master` has saved one. The app module is one big module, so app changes always
  re-run its analysis.
- A [baseline](https://developer.android.com/studio/write/lint#snapshot) (`lint { baseline =
  file("lint-baseline.xml") }`) hides existing issues; it doesn't make lint faster.
- `checkDependencies = true` would lint the libraries from the app module in one report but
  is usually slower. `checkReleaseBuilds = false` (already set) only affects release
  assembly.
- Linting only changed modules needs custom diff-to-module scripting; with the cache it
  would save little here.

### Tests

The unit tests take seconds. Sharding them across jobs or raising `maxParallelForks` would
not pay off until the suites grow.
