# Application release on ECS

The manual `Application release` workflow publishes five ARM64 images (four applications and Keycloak) and can update the four ECS applications. Terraform owns infrastructure and desired counts; the workflow owns application task revisions. Kafka, Redis and Keycloak are not updated by this workflow; Keycloak runs the image of the Terraform `image_tag`.

## Prerequisites

- A GitHub repository with `main` and the checked-in workflows, plus an AWS account and a human identity permitted to manage the Terraform resources.
- Terraform inputs `github_repository` (`owner/repository`), `github_owner_id` and `github_repository_id` (positive numeric strings). Get the IDs from GitHub repository/owner metadata; example test IDs are not real configuration.
- Terraform input `allowed_ingress_cidrs`: the IPv4 networks allowed to call the ALB, for example `["203.0.113.10/32"]` with your own public address. `0.0.0.0/0` is rejected. When your address changes, update the excluded `.tfvars` file and apply again. The first apply after this input was introduced replaces the former single ALB ingress rule, so expect a short gap in ALB access during that apply.
- The immutable OIDC subject format: `repo:owner@owner_id/repository@repository_id:ref:refs/heads/main`. New repositories use this format according to [GitHub's current AWS OIDC guide](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws). Older repositories must enable/check it before use. There is no legacy subject fallback.
- Do not add a workflow `environment:` without deliberately changing the trust model; it changes the subject claim.

Keep personal input values in an excluded `.tfvars` file; the Terraform README commands use `infra/terraform/orderflow.tfvars` (`*.tfvars` is ignored by Git). The deploy role has exact application ECR/ECS scopes and PassRole for the project's two runtime roles. Only the two AWS jobs (image publishing and release) request an OIDC token; verification jobs have no AWS session. [ECS authorization reference](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ecs.html) explains the task-definition resource scopes and the unscoped DescribeTaskDefinition action.

The GitHub OIDC provider is account-wide. If this account already has one (for example created by another repository's stack), set `create_github_oidc_provider = false` in the `.tfvars` file: the stack then reads the existing provider (it must already exist, otherwise the plan fails) and trusts it without managing it, so a teardown here cannot remove the OIDC trust other repositories rely on. With the default `true`, this stack creates and owns the provider.

If this state already manages the provider, whether this stack created it or it was imported by following an earlier version of this guide, remove it from the state before switching to `false`. Otherwise the next apply destroys the account-wide provider. The unindexed address works both before and after Terraform has applied the provider's move to an indexed address:

```bash
terraform state rm aws_iam_openid_connect_provider.github
```

Then check that `terraform plan` shows no destroy of the provider.

Set repository **Actions variables**:

| Variable | Source |
|---|---|
| `AWS_REGION` | Terraform `region` input |
| `AWS_ACCOUNT_ID` | `terraform output -raw aws_account_id` |
| `PROJECT_NAME` | Terraform `project_name` input |
| `ECS_CLUSTER` | `terraform output -raw cluster_name` |
| `AWS_DEPLOY_ROLE_ARN` | `terraform output -raw github_deploy_role_arn` |

Cluster name must equal the project prefix. Role ARN must identify `<project>-github-deploy` in the same account. Do not create AWS access-key secrets for this workflow.

## First startup

1. Review a Terraform plan for `runtime_enabled=false` and `image_tag` set to the full 40-character SHA of the main commit you will dispatch. Apply it with the human AWS identity. All seven services/endpoints exist with zero tasks.
2. Set the five repository variables, then Actions → Application release → Run workflow → branch **main**, operation **publish**. The workflow verifies all four services with Testcontainers, then publishes absent immutable SHA tags. It reuses existing tags only after ARM64 inspection. Publication alone does not start ECS tasks.
3. Review and apply Terraform with the **same** `image_tag` and `runtime_enabled=true`. This activates all seven services with a fresh Service Connect endpoint snapshot.
4. Inspect ECS service/task state and ALB target readiness. This is live validation, not implied by a successful local mock test.
5. Read the demo-user password from SSM (see the Terraform README, Keycloak section) and check authentication against the ALB from an address in `allowed_ingress_cidrs`. `scripts/smoke.sh` cannot run here: its SQL checks need the private RDS, which the Mac cannot reach. Check the HTTP path by hand instead:
   ```bash
   TOKEN=$(curl -s -d grant_type=password -d client_id=orderflow-cli -d username=standard \
     --data-urlencode "password=$DEMO_USER_PASSWORD" "http://<alb-dns>/realms/orderflow/protocol/openid-connect/token" | jq -r .access_token)
   curl -i -X POST "http://<alb-dns>/api/v1/orders" -H "Authorization: Bearer $TOKEN" \
     -H "Idempotency-Key: live-$(date +%s)" -H 'Content-Type: application/json' -d '{"lines":[{"sku":"CHAIR-001","quantity":1}]}'
   curl -s "http://<alb-dns>/api/v1/orders" -H "Authorization: Bearer $TOKEN"
   ```
   The order should be accepted (202) and later listed as CONFIRMED.

The deploy helper renders each new revision from the latest ACTIVE revision of the application's task-definition family and replaces only the image. A Terraform change to an application's environment, secrets or resources registers a new revision; the next application deploy carries it out. To roll it out immediately, dispatch a release from main. A family that changes while a release is running (for example a `terraform apply` meanwhile) stops that service before registration; dispatch the release again. The run summary shows which revision served as the recipe. Terraform keeps old application revisions ACTIVE (`skip_destroy`), so a rollback to an earlier revision stays possible.

Keep `runtime_enabled=true` in subsequent Terraform inputs while the runtime should remain active. Changing it back to false stops the services. The pipeline never changes desired counts or Terraform state. `image_tag` changes alone do not replace the application's selected task revision because Terraform ignores application `task_definition` drift. The next application deploy renders from the newest revision, so configuration registered by Terraform is not lost.

## Later deployment

Dispatch from **main**, operation **deploy**. The source is that dispatch's `github.sha`; there is no arbitrary source-ref input.

All four Maven builds and container tests must pass. A separate publishing job then builds whichever of the five images are absent, in parallel, one matrix job per image with its own 30-minute timeout and credential. The release job only checks images and updates services: four services one after another, each waiting at most about 10 minutes (`aws ecs wait services-stable`) for stability, within its 60-minute timeout and one-hour credential. Every image must exist in ECR with an inspected Linux ARM64 configuration before the helper registers or updates any ECS service. Images use immutable full SHA tags; task definitions select the corresponding digest. Authentication, network and repository errors are failures, not a reason to rebuild an image as if it were missing.

Preflight requires all four application services to be active, nonzero and stable. The running revision only has to be stable; it may already be INACTIVE. A running deployment or disabled runtime rejects the operation before the first mutation. Deployment order is catalog → inventory → order → notification. Secrets, environment, ports, roles and resources are taken from the newest family revision; only the main application image changes. New task revisions have Project and ManagedBy tags.

After each update the helper waits for stability and checks that the requested task revision is PRIMARY/COMPLETED, running count equals desired count, pending count is zero and no older deployment remains. An ECS circuit-breaker rollback to the old stable version leaves the workflow **failed**. See [ECS circuit-breaker behavior](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-circuit-breaker.html).

The run summary contains image digests and previous/requested revision ARNs. It does not export the task JSON or secret/config values. Earlier services can already be upgraded when a later service fails; this is not an atomic release. The summary identifies completed, failed and unattempted services. Inspect ECS before retrying after failure or timeout.

The target concurrency group uses account/region/cluster with `cancel-in-progress: false`. A running release is not cancelled by a new dispatch; GitHub may replace an older pending dispatch. External AWS-console changes do not participate in this lock.

## Rollback

Dispatch from main, operation **rollback**, select one application and supply the **full previous task-definition ARN with revision**, taken from the earlier summary or AWS. Example shape:

```text
arn:aws:ecs:eu-central-1:123456789012:task-definition/orderflow-order-service:7
```

The helper checks account, region, family, ACTIVE status, project roles/container/runtime and the image's ECR presence/ARM64 configuration before updating. Unlike a deploy, a rollback also runs on a currently unstable service, because replacing a failing deployment is its purpose; stability is not required, but the service must still exist and be active, and a disabled runtime (desired count 0) is still rejected. Rollback reuses the existing task revision: no Maven/image build or task registration. Success requires that exact revision to become stable.

The five-image ECR retention policy may delete a rollback image; select a revision whose image still exists. Rollback restores code and runtime configuration, **not** database migrations or business events. A rollback that undoes configuration lasts only until the next release, which renders from the newest family revision again; to make it permanent, fix the Terraform configuration (or deregister the newer revisions) before the next release. Use compatible Flyway schema changes. Roll back each affected application explicitly if a release was partial.

Revisions created before the database password moved to the RDS-managed Secrets Manager secret reference a removed SSM parameter and images without the RDS CA bundle; a rollback cannot cross that change.

## Local evidence and pending cloud validation

The local step checks Terraform mock assertions/graph, Python CLI/workflow contracts, actionlint, existing CI/script tests, and all four full Maven coverage builds. Mock responses do not prove live IAM/API authorization.

With a real repository and AWS identity, verify: exact main OIDC login, non-main rejection, new and existing SHA publication, first activation, normal deploy, a failed deployment with circuit-breaker rollback and failed pipeline, and explicit service rollback. These operations have not been run by this implementation task. The demo's ephemeral Kafka/Redis data, private RDS access and cost/teardown responsibilities are described in the Terraform runbook; deployment automation does not change those constraints.
