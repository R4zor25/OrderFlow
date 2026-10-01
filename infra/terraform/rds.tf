resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-database"
  subnet_ids = sort(data.aws_subnets.default.ids)

  lifecycle {
    precondition {
      condition     = length(distinct([for subnet in data.aws_subnet.default : subnet.availability_zone])) >= 2
      error_message = "RDS requires default subnets in at least two availability zones."
    }
  }
}

resource "aws_db_instance" "main" {
  identifier     = "${var.project_name}-postgres"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = "db.t4g.micro"
  db_name        = "orderflow"
  username       = "orderflow"
  # RDS creates the password and keeps it in Secrets Manager, so it never reaches the Terraform state.
  manage_master_user_password = true
  port                        = 5432
  db_subnet_group_name        = aws_db_subnet_group.main.name
  vpc_security_group_ids      = [aws_security_group.database.id]
  publicly_accessible         = false
  multi_az                    = false
  allocated_storage           = 20
  storage_type                = "gp3"
  storage_encrypted           = true

  backup_retention_period = 0
  deletion_protection     = false
  skip_final_snapshot     = true
}
