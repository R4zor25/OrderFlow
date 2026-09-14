mock_provider "aws" {
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
variables {
  image_tag             = "66dbedbd4866e4e2ba794c10895d27e811511d72"
  github_repository     = "test-org/orderflow"
  github_owner_id       = "100"
  github_repository_id  = "200"
  allowed_ingress_cidrs = ["203.0.113.10/32"]
}
run "reject_invalid_project_name" {
  command = plan
  variables { project_name = "OrderFlow!" }
  expect_failures = [var.project_name]
}
run "reject_other_postgres_major" {
  command = plan
  variables { db_engine_version = "18" }
  expect_failures = [var.db_engine_version]
}
run "reject_single_availability_zone" {
  command = plan
  override_data {
    target = data.aws_subnet.default["subnet-bbbbbbbbbbbbbbbbb"]
    values = { availability_zone = "eu-central-1a" }
  }
  expect_failures = [aws_db_subnet_group.main]
}
run "reject_public_ingress_cidr" {
  command = plan
  variables { allowed_ingress_cidrs = ["0.0.0.0/0"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "reject_empty_ingress_cidrs" {
  command = plan
  variables { allowed_ingress_cidrs = [] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "reject_too_many_ingress_cidrs" {
  command = plan
  variables { allowed_ingress_cidrs = [for i in range(11) : "203.0.113.${i}/32"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "reject_invalid_ingress_cidr" {
  command = plan
  variables { allowed_ingress_cidrs = ["not-a-cidr"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "reject_misaligned_ingress_cidr" {
  command = plan
  variables { allowed_ingress_cidrs = ["203.0.113.10/24"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "reject_ipv6_ingress_cidr" {
  command = plan
  variables { allowed_ingress_cidrs = ["2001:db8::/32"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "deduplicates_ingress_cidrs" {
  command = plan
  variables { allowed_ingress_cidrs = ["203.0.113.10/32", "203.0.113.10/32", "198.51.100.0/24"] }
  assert {
    condition     = toset(keys(aws_vpc_security_group_ingress_rule.alb_http)) == toset(["203.0.113.10/32", "198.51.100.0/24"])
    error_message = "Each distinct allowed CIDR must produce exactly one ALB ingress rule."
  }
}
run "reject_zero_prefix_with_leading_zero" {
  command = plan
  variables { allowed_ingress_cidrs = ["0.0.0.0/00"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
run "reject_non_canonical_prefix" {
  command = plan
  variables { allowed_ingress_cidrs = ["10.0.0.0/08"] }
  expect_failures = [var.allowed_ingress_cidrs]
}
