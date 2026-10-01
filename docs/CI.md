# Continuous integration

`.github/workflows/ci.yml` runs on pull requests and pushes to `main`.
It verifies source changes and builds images; it does not publish images or deploy.
All jobs use GitHub-hosted `ubuntu-24.04-arm` runners, matching the ARM64 container
target. A running Docker daemon is needed for the real Testcontainers tests.

## Changed-service policy

`.github/filters.yaml` defines four service filters:

| Change | Service jobs |
|---|---|
| `services/<service>/**` | That service |
| Multiple service directories | Those services |
| `contracts/**`, `.github/workflows/ci.yml`, `.github/filters.yaml` | All four |
| Root `pom.xml`, Maven wrappers or `.mvn/**` | All four |
| `infra/**`, `scripts/**`, `.github/workflows/deploy.yml` | None (see the check jobs below) |
| Only README or documentation | None |

`.github/check-filters.yaml` selects the other checks:

| Check job | Selected by |
|---|---|
| `terraform`: `fmt -check`, `init -backend=false`, `validate`, `test` (Terraform 1.16.5) | `infra/terraform/**`, the CI workflow or its filters |
| `actionlint` (1.7.12, image pinned by digest) | `.github/workflows/**` or the check filters |
| `docker-images` (`scripts/tests_docker`) and `compose-stack` (`scripts/tests_compose`) | `services/**`, `infra/**`, `scripts/**`, `contracts/**`, the CI workflow or its filters |

The two Docker suites run in parallel on the ARM runner and upload their evidence
(`target/image-verification/`, `target/compose-verification/` including `memory.json`)
for seven days.

The detector still runs for documentation changes. It tests the filter configuration
against real Git diffs using the same pinned dorny action, runs the fast script-helper
tests, then calculates the matrix. PR changes come from the GitHub pull-request API;
main pushes compare the event's previous commit with the pushed branch. Checkout
fetches complete history for push comparisons. The filter tests exercise the git
path locally; they do not simulate the authenticated pull-request API.

## Service jobs

Each selected service runs independently:

1. Check out the source and set up Temurin Java 25.
2. Restore Maven dependency caches keyed by the service POM.
3. Run `./mvnw -B -ntp -Pcoverage verify` in that service directory.
4. Require non-empty JaCoCo XML and HTML reports.
5. Upload available Surefire/Failsafe and coverage reports, including after failure.
6. Build its Dockerfile for `linux/arm64`, without pushing. BuildKit caches have
   separate service scopes. This checks packaging; it does not run the built image.

JaCoCo 0.8.15 is enabled only by the `coverage` Maven profile. Reports are written
to `target/site/jacoco/`, with execution data in `target/jacoco.exec`. Existing
Mockito agent configuration in order and inventory preserves JaCoCo's prepared
agent through late `@{argLine}` evaluation. Ordinary builds retain an empty default
for that property. No coverage percentage threshold is enforced; report existence
and successful tests are required. Coverage measures exercised code, not assertion
quality or correctness.

Artifacts are named `reports-<service>` and retained for seven days. If compilation
fails before any report exists, the upload warns about missing files; the failed
build remains failed. Matrix jobs use `fail-fast: false` so another service's
failure does not cancel their diagnostic results. A newer push to the same pull
request cancels its older run; pushes to `main` never cancel each other, because a
later documentation-only push would otherwise leave an earlier service change untested.

## Required status and permissions

`CI result` is the stable final check. It succeeds only when detection/tests succeed,
every selected service and check job succeeds, and every unselected job was skipped.
Failure, cancellation or skipping of a selected job fails this check. Configure it as the required status after the workflow has run on
GitHub. Do not require each dynamic matrix job individually.

The workflow uses read-only repository permissions, with pull-request read access
only for detection. It runs on `pull_request`, not `pull_request_target`; it needs
no application, database or AWS secrets. Action versions are pinned to full commit
SHAs with release comments. The filter regression checkout uses the same SHA as
the actual filter action.

Branch protection is a repository setting and is not enabled by committing this
workflow. Require PRs and the `CI result` check for `main`, and decide whether to
require the branch to be up to date. Enforcement for administrators and available
features depend on repository settings/plan. See GitHub's
[protected-branch documentation](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).

## Local verification and remaining hosted checks

```bash
./mvnw -B -ntp -Pcoverage clean verify
python3 -m unittest discover -s scripts/tests -v
python3 -m unittest discover -s scripts/tests_docker -v
```

The CI-filter regression suite additionally needs Node and the pinned action's
`dist/index.js` in `PATHS_FILTER_ACTION`; see [script instructions](../scripts/README.md#ci-filter-regression-tests).
An installed `actionlint` can statically validate the workflow:

```bash
actionlint .github/workflows/ci.yml
```

These checks do not execute GitHub orchestration, permissions, cache transport or
artifact upload. This repository currently has no configured remote. After
publishing it, verify one inventory-only PR, one shared-contract/configuration PR,
and one documentation-only PR; confirm the matrix, artifacts and final check.
Then enable branch protection. The full Compose smoke/oversell tests remain
separately runnable; this workflow's service jobs run Maven tests and image builds.

## Manual release workflow

`deploy.yml` is a separate manual workflow. Publish/deploy reverify all four services; rollback skips those builds but retains target/helper checks. Only its release job requests an OIDC token. The existing CI also executes `scripts/tests_deploy` regression tests. Setup, bootstrap ownership and explicit rollback inputs are documented in [DEPLOYMENT.md](DEPLOYMENT.md). Hosted runs remain pending.
