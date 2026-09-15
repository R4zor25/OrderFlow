data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
locals {
  task_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:*" }
      }
    }]
  })
}
resource "aws_iam_role" "execution" {
  name               = "${var.project_name}-task-execution"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role" "task" {
  name               = "${var.project_name}-task"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role_policy" "execution" {
  name = "runtime-bootstrap"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = ["*"] },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = [for repository in aws_ecr_repository.service : repository.arn] },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = concat([for group in aws_cloudwatch_log_group.runtime : "${group.arn}:*"], ["${aws_cloudwatch_log_group.service_connect.arn}:*", "${aws_cloudwatch_log_group.keycloak.arn}:*"]) },
      { Effect = "Allow", Action = ["ssm:GetParameters"], Resource = concat([aws_ssm_parameter.db_password.arn], [for parameter in aws_ssm_parameter.keycloak : parameter.arn]) }
    ]
  })
}
