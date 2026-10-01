mock_provider "aws" {
  mock_resource "aws_db_instance" {
    defaults = {
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:rds!db-demo", kms_key_id = "alias/aws/secretsmanager", secret_status = "active" }]
    }
  }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_data "aws_partition" { defaults = { partition = "aws" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/orderflow-runtime" } }
  mock_resource "aws_lb_target_group" { defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:targetgroup/demo/1234567890123456" } }
  mock_resource "aws_lb" { defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:loadbalancer/app/demo/1234567890123456" } }
  mock_resource "aws_lb_listener" { defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:listener/app/demo/1234567890123456/1234567890123456" } }
  mock_resource "aws_service_discovery_http_namespace" { defaults = { arn = "arn:aws:servicediscovery:eu-central-1:123456789012:namespace/ns-demo" } }
  mock_resource "aws_ecs_task_definition" { defaults = { arn = "arn:aws:ecs:eu-central-1:123456789012:task-definition/demo:1" } }

  mock_data "aws_vpc" {
    defaults = { id = "vpc-0123456789abcdef0" }
  }
  mock_data "aws_subnets" {
    defaults = { ids = ["subnet-aaaaaaaaaaaaaaaaa", "subnet-bbbbbbbbbbbbbbbbb"] }
  }
  mock_data "aws_subnet" {
    defaults = { availability_zone = "eu-central-1a" }
  }
}
override_data {
  target = data.aws_subnet.default["subnet-bbbbbbbbbbbbbbbbb"]
  values = { availability_zone = "eu-central-1b" }
}
override_resource {
  target = aws_security_group.tasks
  values = { id = "sg-aaaaaaaaaaaaaaaaa" }
}
override_resource {
  target = aws_security_group.database
  values = { id = "sg-bbbbbbbbbbbbbbbbb" }
}
override_resource {
  target = aws_iam_role.execution
  values = { arn = "arn:aws:iam::123456789012:role/orderflow-task-execution" }
}
override_resource {
  target = aws_iam_role.task
  values = { arn = "arn:aws:iam::123456789012:role/orderflow-task" }
}
variables {
  image_tag             = "66dbedbd4866e4e2ba794c10895d27e811511d72"
  github_repository     = "test-org/orderflow"
  github_owner_id       = "100"
  github_repository_id  = "200"
  allowed_ingress_cidrs = ["203.0.113.10/32"]
}
run "github_identity_and_permissions" {
  command = apply
  assert {
    condition     = jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] == "repo:test-org@100/orderflow@200:ref:refs/heads/main"
    error_message = "Trust must require exact immutable main identity."
  }
  assert {
    condition = tomap(jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringEquals) == tomap({
      "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
      "token.actions.githubusercontent.com:sub" = "repo:test-org@100/orderflow@200:ref:refs/heads/main"
    })
    error_message = "Trust must contain only exact audience and immutable subject conditions."
  }
  assert {
    condition = toset(flatten([for s in jsondecode(aws_iam_role_policy.github_deploy.policy).Statement : s.Action])) == toset([
      "ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:DescribeImages", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecs:DescribeServices", "ecs:UpdateService", "ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition", "ecs:TagResource", "iam:PassRole"
    ])
    error_message = "Deploy role must not acquire secret, infrastructure or admin actions."
  }
  assert {
    condition     = toset(jsondecode(aws_iam_role_policy.github_deploy.policy).Statement[1].Resource) == toset([for r in aws_ecr_repository.service : r.arn]) && toset(jsondecode(aws_iam_role_policy.github_deploy.policy).Statement[2].Resource) == toset([for s in aws_ecs_service.application : s.id])
    error_message = "Image and service actions must be restricted to application resources."
  }
  assert {
    condition     = toset(jsondecode(aws_iam_role_policy.github_deploy.policy).Statement[4].Resource) == toset([for n in keys(local.applications) : "arn:aws:ecs:eu-central-1:123456789012:task-definition/orderflow-${n}:*"])
    error_message = "Register and tag operations must use project application task families."
  }
  assert {
    condition     = toset(jsondecode(aws_iam_role_policy.github_deploy.policy).Statement[5].Resource) == toset([aws_iam_role.execution.arn, aws_iam_role.task.arn]) && jsondecode(aws_iam_role_policy.github_deploy.policy).Statement[5].Condition.StringEquals["iam:PassedToService"] == "ecs-tasks.amazonaws.com"
    error_message = "PassRole must only pass the two runtime roles to ECS tasks."
  }

}

run "reject_invalid_repository" {
  command = plan
  variables { github_repository = "test-org/*" }
  expect_failures = [var.github_repository]
}

run "reject_invalid_owner_id" {
  command = plan
  variables { github_owner_id = "0" }
  expect_failures = [var.github_owner_id]
}

run "reject_invalid_repository_id" {
  command = plan
  variables { github_repository_id = "abc" }
  expect_failures = [var.github_repository_id]
}
