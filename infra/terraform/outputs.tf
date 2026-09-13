output "vpc_id" {
  description = "Existing default VPC used by the demo."
  value       = data.aws_vpc.default.id
}

output "subnet_ids" {
  description = "Existing default subnet IDs for later runtime resources."
  value       = sort(data.aws_subnets.default.ids)
}

output "task_security_group_id" {
  description = "Security group for the later ECS tasks."
  value       = aws_security_group.tasks.id
}

output "database_endpoint" {
  description = "RDS hostname and port; reachable only from the task security group."
  value       = aws_db_instance.main.endpoint
}

output "database_name" {
  description = "Shared demo database with one Flyway schema per service."
  value       = aws_db_instance.main.db_name
}

output "database_username" {
  description = "Demo database user; production should use separate service users."
  value       = aws_db_instance.main.username
}

output "database_password_parameter_arn" {
  description = "SSM parameter ARN for later ECS secret injection; not the password value."
  value       = aws_ssm_parameter.db_password.arn
}

output "ecr_repository_urls" {
  description = "Image repository URL keyed by service name."
  value       = { for name, repository in aws_ecr_repository.service : name => repository.repository_url }
}

output "cluster_name" {
  description = "ECS cluster name for deployments."
  value       = aws_ecs_cluster.main.name
}
output "service_names" {
  description = "ECS service names keyed by application or infrastructure service."
  value       = { for name, service in merge(aws_ecs_service.application, aws_ecs_service.infrastructure) : name => service.name }
}
output "task_definition_arns" {
  description = "Terraform task revisions; deployments can select newer application revisions."
  value       = { for name, task in merge(aws_ecs_task_definition.application, aws_ecs_task_definition.infrastructure) : name => task.arn }
}
output "alb_dns_name" {
  description = "Public entry point; runtime-disabled bootstrap has no healthy targets."
  value       = aws_lb.main.dns_name
}
output "runtime_enabled" {
  description = "Whether Terraform requests one task per service."
  value       = var.runtime_enabled
}
output "image_tag" {
  description = "Application image SHA selected for Terraform task definitions."
  value       = var.image_tag
}
output "github_deploy_role_arn" {
  description = "Short-lived GitHub OIDC deployment role."
  value       = aws_iam_role.github_deploy.arn
}
output "aws_account_id" {
  description = "Expected deployment account for GitHub repository variables."
  value       = data.aws_caller_identity.current.account_id
}
