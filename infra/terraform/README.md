# OrderFlow AWS runtime

Terraform defines a temporary ARM64 ECS Fargate environment in `eu-central-1`.
Local provider-schema and mock tests pass without AWS credentials. No live
AWS plan/apply or cloud application traffic has been verified. GitHub OIDC and
the deployment workflow are not implemented yet.

## Resources

- Five ECR repositories (four services and Keycloak) with immutable tags, scan on push and a policy retaining
  the five latest images. Expiration is asynchronous and can remove rollback images.
- Existing default VPC/subnets in at least two availability zones; no new VPC or NAT.
- Non-public PostgreSQL 17 RDS, `db.t4g.micro`, Single-AZ, encrypted 20 GiB gp3.
- RDS-managed master password in AWS Secrets Manager (`manage_master_user_password`); it is not in the Terraform state.
- One ECS cluster, Cloud Map HTTP namespace, seven independent Fargate services:
  catalog-pricing, order, inventory, notification, Kafka, Redis and Keycloak.
- One internet-facing HTTP ALB, five IP target groups, path routing and default 404.
- Seven application/infrastructure log groups (six runtime plus Keycloak) and a Service Connect proxy log group,
  each retained for three days; separate execution role and application task role.

| Input | Default | Constraint |
| --- | --- | --- |
| `region` | `eu-central-1` | One region for the demo |
| `project_name` | `orderflow` | 2–20 lowercase letters/digits, starting with a letter |
| `db_engine_version` | `17` | Confirm regional availability of PostgreSQL 17/17.x |
| `image_tag` | Required | Full 40-character lowercase Git SHA; no `latest` |
| `runtime_enabled` | `false` | False: seven services with zero tasks; true: one task each |

Every task uses Linux ARM64, Fargate platform 1.4.0, `awsvpc`, 512 CPU units and
1024 MiB. The main container has 256 CPU units (relative CPU shares), with hard
memory limits of 768 MiB for Spring/Kafka, 512 MiB for notification and 384 MiB for
Redis. Remaining task resources accommodate the managed Service Connect proxy;
these are initial demo sizes, not production capacity measurements.

## Network and routing

| Service | Internal alias | Proxy ingress | Public path |
| --- | --- | --- | --- |
| catalog-pricing-service | `catalog-pricing:8081` | 18081 | `/api/v1/products`, `/api/v1/products/*` |
| order-service | `order:8082` | 18082 | `/api/v1/orders`, `/api/v1/orders/*` |
| inventory-service | `inventory:8083` | 18083 | `/api/v1/stock`, `/api/v1/stock/*`, `/api/v1/reservations` |
| notification-service | `notification:8084` | 18084 | `/api/v1/notifications` |
| kafka | `kafka:9092` | 19092 | None |
| redis | `redis:6379` | 16379 | None |
| keycloak | `keycloak:8080` | 18180 | `/realms/*`, `/admin/*`, `/resources/*` (priority 5) |

Tasks have public IPs for outbound image/log/secret access. Their security group
accepts original HTTP application ports only from the ALB group, and proxy ports
only from the task group. RDS accepts TCP 5432 only from tasks. Public IP assignment
does not add public inbound access. Default subnet routes and network ACLs must
still permit this traffic in the real account.

The ALB security group accepts TCP 80 only from the required `allowed_ingress_cidrs`
input: 1–10 IPv4 network CIDR blocks, typically your public address as `/32`;
`0.0.0.0/0` is rejected because the ALB, including Keycloak, serves plain HTTP. `POST /api/v1/quotes`
is not routed by the ALB; order-service reaches it through the `catalog-pricing:8081`
Service Connect alias.

Service Connect uses the original ports in client aliases and different
`ingress_port_override` ports for proxy-to-proxy traffic. ALB traffic goes directly
to the original application ports. The namespace is a Cloud Map HTTP namespace,
not a general DNS zone usable from the Mac.

The inventory stock/reservation and notification inspection HTTP APIs are not
implemented yet. Their ALB rules prepare routing; they do not create endpoints.
The existing catalog/order APIs keep their original paths; no rewriting occurs.

## Health and deployment

Container health checks and ALB target groups both use liveness:
`/actuator/health/liveness` for Spring and `/health/liveness` for Micronaut,
requiring HTTP 200. Target groups check liveness, so dependency outages do not
replace tasks. Application services have 90 seconds health grace, 100/200
healthy deployment percentages and circuit breaker with rollback. Java container
stop timeout and ALB deregistration delay are both 30 seconds.

The first unsuccessful deployment has no previous completed revision to roll
back to. Terraform resource creation dependencies do not prove dependency readiness.
ECS retries unhealthy startup; logs, service events and targets must be inspected.

## Runtime configuration and secrets

Spring uses its `aws` profile and service-specific `CATALOG_DB_*`, `ORDER_DB_*`,
`INVENTORY_DB_*` inputs. Catalog/order use `CATALOG_REDIS_HOST`/`ORDER_REDIS_HOST`.
Order/inventory receive `KAFKA_BOOTSTRAP=kafka:9092`; order's pricing URL is
`http://catalog-pricing:8081`. Inventory outbox relay is explicitly enabled.

Notification uses `MICRONAUT_ENVIRONMENTS=aws`, `NOTIFICATION_DB_*` and Kafka inputs.
Its AWS configuration enables the consumer and Kafka readiness, uses LOG delivery
and disables JavaMail. No email is sent on this deployment channel.

Flyway creates `catalog`, `orders`, `inventory`, `notifications` in database
`orderflow`. URLs include `currentSchema` and `sslmode=verify-full` with
`sslrootcert` pointing at the AWS RDS CA bundle built into each image
(`/application/rds-global-bundle.pem`, Keycloak `/opt/keycloak/conf/rds-global-bundle.pem`),
so the certificate and the hostname are verified. The shared demo user is not
production isolation.

The execution role pulls ECR images, writes existing log streams and reads only
the four Keycloak SSM parameters via `ssm:GetParameters` and the RDS-managed database
secret via `secretsmanager:GetSecretValue`. ECS injects them with the container `secrets`
field; the database password is the secret's `password` key. RDS rotates that secret
every seven days by default and ECS does not refresh it in running tasks: within about
30 minutes (Hikari's connection lifetime) every database call fails, while liveness and
the ALB health checks stay green. Force a new deployment after each rotation.
After applying this configuration to a stack that still used the generated SSM
password, dispatch a release immediately: the running revisions reference the removed
parameter. The images pin the AWS RDS CA bundle by SHA-256; when AWS publishes a new
`global-bundle.pem`, image builds fail until the checksum in all five Dockerfiles
(four services and `infra/keycloak`) is recomputed and updated together. Builds need
access to `truststore.pki.rds.amazonaws.com`. The separate task role has no attached permissions;
application code makes no AWS API calls. No GitHub deploy role exists yet.

Kafka (`apache/kafka:4.3.1`) is one combined KRaft node, replication factor one,
heap `-Xms256m -Xmx512m`, advertised broker `kafka:9092`, controller `localhost:9093`.
Kafka broker auto-topic creation is disabled. A non-essential `kafka-topics` init
container in the Kafka task creates the two business topics and their DLTs once the
broker is healthy, so a replaced broker has its topics before the applications
restart; Spring's topic configuration creates the same topics locally. Redis uses
`redis:7.4.11-bookworm`. Kafka and Redis redeploy without overlap (0/100): a short
outage instead of two instances behind one alias. Both run on ephemeral task storage.
Replacing Kafka loses events/offsets; replacing Redis loses cache and idempotency keys.
Stop the demo for infrastructure updates and use durable managed alternatives for
production.

## Local checks without AWS credentials

Use Terraform 1.16.5 (compatible later 1.16 patches are allowed) and the committed
provider lockfile. From the repository root:

```bash
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform test
python3 -m unittest discover -s infra/terraform/tests -p 'test_*.py'
```

The seven native test runs use a mock AWS provider. `command = apply` creates
transient mock state, not cloud resources. Tests cover bootstrap/activation,
ports, resource budget, profiles, secret injection, routing, IAM and invalid
inputs. A separate Python standard-library test traverses the generated Terraform
dependency graph to verify ALB forwarding association precedes ECS service creation.
They cannot prove actual AWS permissions, capacity, connectivity, ALB
traffic, Service Connect behavior or circuit breaker rollback.

## Keycloak

Keycloak runs the `${project}/keycloak` image (built from `infra/keycloak`, same SHA as
the applications) in production mode with `KC_HTTP_ENABLED=true` behind the HTTP ALB.
Its hostname is the ALB DNS name, so every token carries the issuer
`http://<alb-dns>/realms/orderflow`; catalog and order fetch keys and service tokens
through `keycloak:8080`. State lives in the `public` schema of database `orderflow`.
The task has 1 vCPU / 2 GB (one more Fargate task to budget) and runs one task at a time.

Terraform generates the bootstrap-admin password, the `order-service` client secret
and the demo-user password into SSM SecureString parameters (`/<project>/keycloak/*`);
the admin user name is `orderflow-admin`. Keycloak is not configured to trust `X-Forwarded-*` headers: it sees the ALB's private address, which keeps HTTP requests acceptable under the realm's default SSL rule, so its event logs show the ALB address rather than the client's. Every Terraform apply that changes `image_tag` restarts the single Keycloak task, so sign-in is unavailable for one to two minutes. Read the demo password after apply:

```bash
aws ssm get-parameter --with-decryption --query Parameter.Value --output text \
  --name "$(terraform -chdir=infra/terraform output -json keycloak_parameter_names | jq -r .demo_password)"
```

The realm is imported only when it does not exist yet. A changed realm file needs a
new image and `terraform apply`; to re-import it completely, drop the Keycloak tables
from the `public` schema first. The admin console (`/admin/`) is reachable only from
`allowed_ingress_cidrs` and over plain HTTP.

## Real bootstrap and activation (requires AWS access)

Confirm account/region, default subnets in two AZs, available PostgreSQL 17 minor
and `db.t4g.micro` availability. Estimate RDS, ALB, Fargate, logs/storage and public
IPv4 cost before provisioning; zero tasks still leave chargeable ALB/RDS resources.
There has been no live deployment for this repository.

From the repo root, choose the committed source SHA that will label all five images:

```bash
IMAGE_TAG=$(git rev-parse HEAD)
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform plan -var-file=orderflow.tfvars -var="image_tag=$IMAGE_TAG" \
  -var='runtime_enabled=false' -out=bootstrap.tfplan
```

Inspect that saved plan before explicitly applying it. This first phase registers
all seven services/discovery endpoints with zero tasks. Build and publish the four
ARM64 application images and the Keycloak image under that exact SHA to the output ECR URLs. Image
publication and GitHub deployment automation are a separate setup step.

Then plan activation with the **same SHA**:

```bash
terraform -chdir=infra/terraform plan -var-file=orderflow.tfvars -var="image_tag=$IMAGE_TAG" \
  -var='runtime_enabled=true' -out=activation.tfplan
```

Inspect before applying. All endpoints must already exist: tasks do not pick up
endpoints introduced after their latest deployment. Activation sets
`force_new_deployment=true` on all seven services, since desired-count scaling alone
does not create a deployment. The false-to-true transition requests a fresh
deployment after bootstrap registered all endpoints. An unchanged apply does not
continuously redeploy; later service configuration changes while enabled may force
a deployment, including ephemeral Kafka/Redis replacements. Wait for services, check stopped-task
reasons/logs and ALB target health, then test catalog/order requests. An ALB DNS
output during bootstrap is not proof of a running application.

Application services ignore Terraform changes to `task_definition` so a later
CI-selected revision is preserved. Changing `image_tag` or application configuration
registers new task definitions but **does not update existing application services**.
The next application deploy renders from the newest revision of each family, so
Terraform configuration reaches the services then; old revisions stay ACTIVE
(`skip_destroy`) for rollback. First deployment from zero tasks must also activate
desired count. Other service configuration changes remain managed.

The Compose smoke script uses SQL access to check inventory/notifications. It
cannot connect from the Mac directly to private RDS; cloud smoke needs a separate
observation method. The ALB does not expose Actuator or Micronaut health paths
publicly; target group health checks go directly to tasks.

## State and teardown

Local state and saved plans still contain the generated Keycloak secrets (the database
password is no longer among them). Sensitive output masking is not
state encryption. State/backups, personal tfvars, provider files, crash logs and
`*.tfplan` files stay excluded; keep `.terraform.lock.hcl` in Git. Do not rename
saved plans to unignored files. Keep state to manage the resources; deleting it
does not delete AWS resources. Team use requires a protected remote backend.

RDS has no automatic backup/final snapshot and deletion protection is disabled.
Destroying this demo deletes its data. Inspect and explicitly empty only the
intended ECR repositories before teardown (`force_delete=false`). Use the same
image SHA input when planning teardown:

```bash
terraform -chdir=infra/terraform plan -destroy -var-file=orderflow.tfvars -var="image_tag=$IMAGE_TAG" -out=destroy.tfplan
```

Inspect before explicitly applying. Confirm ECS, Cloud Map, ALB, RDS, log groups,
network interfaces, public IPv4 and ECR resources in the account afterward.
Deleting local state is not teardown.

## References

- [Service Connect networking, startup and proxy resources](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-connect-concepts-deploy.html)
- [Task execution role](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html)
- [SSM secret injection](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-ssm-paramstore.html)
- [Deployment circuit breaker](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-circuit-breaker.html)

## GitHub release identity

Supply required `github_repository`, `github_owner_id`, `github_repository_id` and `allowed_ingress_cidrs` inputs; the GitHub values come from real GitHub metadata. The OIDC trust requires the exact immutable repository identity on main. Personal tfvars stay excluded. Existing GitHub providers must be imported with compatible state ownership. `github_deploy_role_arn` and `aws_account_id` provide the workflow variables. See [deployment setup and first activation](../../docs/DEPLOYMENT.md); publish images before enabling runtime, and preserve `runtime_enabled=true` thereafter.
