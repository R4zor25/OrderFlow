data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }

  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

data "aws_subnet" "default" {
  for_each = toset(data.aws_subnets.default.ids)
  id       = each.value
}

resource "aws_security_group" "tasks" {
  name        = "${var.project_name}-tasks"
  description = "Runtime tasks; inbound application rules are configured with the ECS services."
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_security_group" "database" {
  name        = "${var.project_name}-database"
  description = "PostgreSQL access from application tasks only."
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "database" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.tasks.id
  description                  = "PostgreSQL from runtime tasks."
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "tasks" {
  security_group_id = aws_security_group.tasks.id
  description       = "Outbound access for image pulls, logs and runtime dependencies."
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# The description predates the CIDR restriction; changing it would replace the group attached to the ALB.
resource "aws_security_group" "alb" {
  name        = "${var.project_name}-alb"
  description = "Public HTTP entry point for the demo."
  vpc_id      = data.aws_vpc.default.id
}
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = toset(var.allowed_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = each.value
}
resource "aws_vpc_security_group_egress_rule" "alb_application" {
  for_each                     = local.applications
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}
resource "aws_vpc_security_group_ingress_rule" "application" {
  for_each                     = local.applications
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}
resource "aws_vpc_security_group_ingress_rule" "service_connect" {
  for_each                     = local.runtime_services
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.proxy_port
  to_port                      = each.value.proxy_port
}
