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
