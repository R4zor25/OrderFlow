mock_provider "aws" {
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
run "foundation_contract" {
  command = apply
  assert {
    condition     = toset(keys(aws_ecr_repository.service)) == toset(["catalog-pricing-service", "order-service", "inventory-service", "notification-service"])
    error_message = "All four services need an ECR repository."
  }
  assert {
    condition = (alltrue([for r in aws_ecr_repository.service :
      r.image_tag_mutability == "IMMUTABLE" && r.image_scanning_configuration[0].scan_on_push && !r.force_delete
    ]))
    error_message = "Repositories must preserve immutable images and explicit teardown."
  }
  assert {
    condition     = toset(keys(aws_ecr_lifecycle_policy.service)) == toset(keys(aws_ecr_repository.service))
    error_message = "Every repository must have a lifecycle policy."
  }
  assert {
    condition = (alltrue([for p in aws_ecr_lifecycle_policy.service :
      jsondecode(p.policy).rules[0].selection.countNumber == 5 &&
      jsondecode(p.policy).rules[0].selection.countType == "imageCountMoreThan" &&
      jsondecode(p.policy).rules[0].selection.tagStatus == "any" &&
      jsondecode(p.policy).rules[0].action.type == "expire"
    ]))
    error_message = "Keep only the five latest images in each repository."
  }
  assert {
    condition = (aws_db_instance.main.storage_encrypted && !aws_db_instance.main.publicly_accessible &&
      !aws_db_instance.main.multi_az && aws_db_instance.main.allocated_storage == 20 &&
      aws_db_instance.main.engine == "postgres" && aws_db_instance.main.engine_version == "17" &&
      aws_db_instance.main.vpc_security_group_ids == toset([aws_security_group.database.id]) &&
    aws_db_instance.main.storage_type == "gp3" && aws_db_instance.main.instance_class == "db.t4g.micro")
    error_message = "The database must match the private encrypted demo configuration."
  }
  assert {
    condition = (aws_vpc_security_group_ingress_rule.database.from_port == 5432 &&
      aws_vpc_security_group_ingress_rule.database.to_port == 5432 &&
      aws_vpc_security_group_ingress_rule.database.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.database.security_group_id == aws_security_group.database.id &&
      aws_vpc_security_group_ingress_rule.database.referenced_security_group_id == aws_security_group.tasks.id &&
    aws_vpc_security_group_ingress_rule.database.cidr_ipv4 == null)
    error_message = "Only tasks may connect to the PostgreSQL port."
  }
  assert {
    condition = (aws_ssm_parameter.db_password.type == "SecureString" &&
      aws_ssm_parameter.db_password.value == random_password.database.result &&
    aws_db_instance.main.password == random_password.database.result)
    error_message = "RDS and SSM must use the same generated password."
  }
  assert {
    condition = (aws_db_instance.main.backup_retention_period == 0 &&
    aws_db_instance.main.skip_final_snapshot && !aws_db_instance.main.deletion_protection)
    error_message = "The documented disposable-demo teardown settings must be explicit."
  }
  assert {
    condition = (
      output.database_name == "orderflow" && output.database_username == "orderflow" &&
      output.database_password_parameter_arn == aws_ssm_parameter.db_password.arn &&
      toset(keys(output.ecr_repository_urls)) == toset(keys(aws_ecr_repository.service)) &&
      output.task_security_group_id == aws_security_group.tasks.id &&
      toset(output.subnet_ids) == toset(data.aws_subnets.default.ids) &&
      output.vpc_id == data.aws_vpc.default.id && output.database_endpoint == aws_db_instance.main.endpoint
    )
    error_message = "Runtime handoff outputs must reference the configured resources without a password value."
  }

}
