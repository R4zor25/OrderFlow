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
