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
variables { image_tag = "66dbedbd4866e4e2ba794c10895d27e811511d72" }
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
