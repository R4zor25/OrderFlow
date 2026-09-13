# AWS infrastructure foundation

This module defines the registry, network access and database for a temporary
OrderFlow demo in `eu-central-1`. ECS services, an ALB and GitHub OIDC deployment
are added separately. Applying this module alone does not run the applications.

## Resources and inputs

- Four ECR repositories, immutable tags, scan on push, and a policy expiring
  images beyond the five most recently pushed. Expiration is asynchronous and
  can remove old deployment/rollback images; this is a short-session demo policy.
- Existing default VPC and default subnets in at least two availability zones.
- Task security group with outbound access; application ingress is added with ECS.
- Database security group allowing TCP 5432 only from the task security group.
- Non-public Single-AZ PostgreSQL 17 RDS, `db.t4g.micro`, 20 GiB encrypted gp3.
- A generated 32-character password, shared by RDS and a Standard-tier SSM
  SecureString at `/<project_name>/db/password`.

| Variable | Default | Constraint |
|---|---|---|
| `region` | `eu-central-1` | Use the same region for the whole demo |
| `project_name` | `orderflow` | 2–20 lowercase letters/digits, starting with a letter |
| `db_engine_version` | `17` | PostgreSQL 17 major or available `17.x` minor |

The database name and shared demo user are `orderflow`. Flyway creates each
service's schema at application startup. No database SQL is run by Terraform.
The outputs expose repository URLs, VPC/subnet/task-group IDs, database
endpoint/name/user, and the SSM parameter ARN, never its value.

## Local checks without AWS credentials

Use Terraform 1.16.5 or a compatible later 1.16 patch. Provider versions are
explicitly pinned and recorded in the committed `.terraform.lock.hcl`.
From the repository root:

```bash
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform test
```

`terraform test` mocks the AWS provider. Its `command = apply` runs against mock
resources and transient test state, not an AWS deployment. The real random
provider generates a local password for consistency assertions. No AWS login is
needed. Positive tests cover the resources; negative tests exercise naming,
PostgreSQL major-version and subnet-AZ validation.

These checks validate configuration and provider schemas. They do not verify AWS
account permissions, live default subnets, regional engine availability, instance
capacity or connectivity. There has been no live AWS plan/apply for this module.

## Before a real plan

Use a non-root identity, enable MFA for the root account, and configure a budget
alert before provisioning. Estimate the actual region and sizes in the AWS
Pricing Calculator; budget alerts do not automatically cap spending.
Install/configure AWS CLI with an appropriate non-root identity, then check the
account and region explicitly:

```bash
aws sts get-caller-identity --region eu-central-1
aws ec2 describe-vpcs --region eu-central-1 \
  --filters Name=is-default,Values=true --query 'Vpcs[].VpcId'
aws rds describe-db-engine-versions --region eu-central-1 --engine postgres \
  --query "DBEngineVersions[?starts_with(EngineVersion, '17.')].EngineVersion"
```

Check the selected minor supports `db.t4g.micro` using
`aws rds describe-orderable-db-instance-options`, then pass that available `17.x`
version to the plan. If the default VPC is missing, resolve that prerequisite
before planning; this module does not create a replacement network.

From `infra/terraform`, once authentication and prerequisites are ready:

```bash
terraform init
terraform plan -var='db_engine_version=17.x' -out=demo.tfplan
```

Replace `17.x` with an actual supported version from the query. Inspect the plan
before deciding to run `terraform apply demo.tfplan`. A successful real plan is
still not proof that capacity or connectivity will be available during apply.
There is no `github_repo` input at this stage.

## State and secrets

The local state contains the generated password. `sensitive` redacts normal
output; it does not encrypt the state. State, backups, personal tfvars, provider
files, crash logs and `*.tfplan` files are excluded from Git. Do not rename saved
plans to an unignored file. Protect local copies and avoid sharing raw state,
plan or decrypted SSM output. Keep the provider lockfile in Git.

State is required to manage existing resources; deleting it does not delete AWS
resources. For a team, use a remote backend with encryption, access control and
locking. SSM credential rotation and distinct per-service database users are
production improvements outside this demo.

## Teardown

RDS has no automatic backups or final snapshot in this demo, and deletion
protection is disabled. `terraform destroy` deletes the database and its data.
Keep any needed evidence before running it.

ECR `force_delete` is false. If repositories contain images, explicitly inspect
and remove their contents before teardown. For each repository URL from
`terraform output -json ecr_repository_urls`, use its repository name
`orderflow/<service>` with `aws ecr list-images`; then delete the chosen image
IDs with `aws ecr batch-delete-image`. Use the same account and region. Do not
copy this operation onto unrelated repositories. There is no automatic purge.

```bash
terraform plan -destroy -out=destroy.tfplan
terraform apply destroy.tfplan
```

Finally check the AWS console for resources remaining in the target region.
Do not delete the local state as a substitute for teardown. When later ECS/ALB
resources are added to this module, include them in the teardown and account
checks too.
