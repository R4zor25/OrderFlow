resource "aws_ecs_cluster" "main" {
  name = var.project_name
}
resource "aws_service_discovery_http_namespace" "main" {
  name = "${var.project_name}.internal"
}
locals {
  runtime_services = merge(local.applications, local.infrastructure)
}
resource "aws_cloudwatch_log_group" "runtime" {
  for_each          = local.runtime_services
  name              = "/ecs/${var.project_name}/${each.key}"
  retention_in_days = 3
}
resource "aws_cloudwatch_log_group" "service_connect" {
  name              = "/ecs/${var.project_name}/service-connect"
  retention_in_days = 3
}
