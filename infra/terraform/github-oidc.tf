locals {
  github_subject = format("repo:%s@%s/%s@%s:ref:refs/heads/main",
    try(split("/", var.github_repository)[0], ""), var.github_owner_id,
  try(split("/", var.github_repository)[1], ""), var.github_repository_id)
  github_oidc_provider_arn = (var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn
  : data.aws_iam_openid_connect_provider.github[0].arn)
  application_task_arns = [for name in sort(keys(local.applications)) :
  "arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task-definition/${var.project_name}-${name}:*"]
}

# The provider is account-wide; when another stack already created it, only read it so a teardown here cannot
# remove the OIDC trust that other repositories rely on.
resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_github_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

moved {
  from = aws_iam_openid_connect_provider.github
  to   = aws_iam_openid_connect_provider.github[0]
}

resource "aws_iam_role" "github_deploy" {
  name = "${var.project_name}-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = local.github_subject
      } }
    }]
  })
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "application-release"
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "RegistryLogin", Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Sid = "ApplicationImages", Effect = "Allow", Action = [
        "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:DescribeImages",
        "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"
      ], Resource = [for repository in aws_ecr_repository.service : repository.arn] },
      { Sid = "ApplicationServices", Effect = "Allow", Action = ["ecs:DescribeServices", "ecs:UpdateService"],
      Resource = [for service in aws_ecs_service.application : service.id] },
      { Sid = "ReadTaskRecipe", Effect = "Allow", Action = ["ecs:DescribeTaskDefinition"], Resource = "*" },
      { Sid = "ApplicationRevisions", Effect = "Allow", Action = ["ecs:RegisterTaskDefinition", "ecs:TagResource"],
      Resource = local.application_task_arns },
      { Sid      = "PassRuntimeRoles", Effect = "Allow", Action = ["iam:PassRole"],
        Resource = [aws_iam_role.execution.arn, aws_iam_role.task.arn],
      Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } } }
    ]
  })
}
