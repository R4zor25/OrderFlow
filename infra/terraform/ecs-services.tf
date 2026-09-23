locals {
  applications = {
    "catalog-pricing-service" = {
      alias          = "catalog-pricing"
      port           = 8081
      proxy_port     = 18081
      memory         = 768
      db_prefix      = "CATALOG"
      image          = "${aws_ecr_repository.service["catalog-pricing-service"].repository_url}:${var.image_tag}"
      health_path    = "/actuator/health/liveness"
      health_command = "curl -fsS http://localhost:8081/actuator/health/liveness >/dev/null || exit 1"
      priority       = 10
      paths          = ["/api/v1/products", "/api/v1/products/*"]
      environment = {
        CATALOG_DB_URL          = "jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?currentSchema=catalog&sslmode=require"
        CATALOG_DB_USERNAME     = aws_db_instance.main.username
        SPRING_PROFILES_ACTIVE  = "aws"
        CATALOG_REDIS_HOST      = "redis"
        CATALOG_JWT_ISSUER_URI  = local.keycloak_issuer
        CATALOG_JWT_JWK_SET_URI = "${local.keycloak_internal}/certs"
      }
    }
    "order-service" = {
      alias          = "order"
      port           = 8082
      proxy_port     = 18082
      memory         = 768
      db_prefix      = "ORDER"
      image          = "${aws_ecr_repository.service["order-service"].repository_url}:${var.image_tag}"
      health_path    = "/actuator/health/liveness"
      health_command = "curl -fsS http://localhost:8082/actuator/health/liveness >/dev/null || exit 1"
      priority       = 20
      paths          = ["/api/v1/orders", "/api/v1/orders/*"]
      environment = {
        ORDER_DB_URL             = "jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?currentSchema=orders&sslmode=require"
        ORDER_DB_USERNAME        = aws_db_instance.main.username
        SPRING_PROFILES_ACTIVE   = "aws"
        ORDER_REDIS_HOST         = "redis"
        KAFKA_BOOTSTRAP          = "kafka:9092"
        CATALOG_PRICING_BASE_URL = "http://catalog-pricing:8081"
        ORDER_JWT_ISSUER_URI     = local.keycloak_issuer
        ORDER_JWT_JWK_SET_URI    = "${local.keycloak_internal}/certs"
        ORDER_TOKEN_URI          = "${local.keycloak_internal}/token"
      }
      keycloak_secrets = { ORDER_SERVICE_CLIENT_SECRET = "client_secret" }
    }
    "inventory-service" = {
      alias          = "inventory"
      port           = 8083
      proxy_port     = 18083
      memory         = 768
      db_prefix      = "INVENTORY"
      image          = "${aws_ecr_repository.service["inventory-service"].repository_url}:${var.image_tag}"
      health_path    = "/actuator/health/liveness"
      health_command = "curl -fsS http://localhost:8083/actuator/health/liveness >/dev/null || exit 1"
      priority       = 30
      paths          = ["/api/v1/stock", "/api/v1/stock/*", "/api/v1/reservations"]
      environment = {
        INVENTORY_DB_URL               = "jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?currentSchema=inventory&sslmode=require"
        INVENTORY_DB_USERNAME          = aws_db_instance.main.username
        SPRING_PROFILES_ACTIVE         = "aws"
        KAFKA_BOOTSTRAP                = "kafka:9092"
        INVENTORY_OUTBOX_RELAY_ENABLED = "true"
        INVENTORY_JWT_ISSUER_URI       = local.keycloak_issuer
        INVENTORY_JWT_JWK_SET_URI      = "${local.keycloak_internal}/certs"
      }
    }
    "notification-service" = {
      alias          = "notification"
      port           = 8084
      proxy_port     = 18084
      memory         = 512
      db_prefix      = "NOTIFICATION"
      image          = "${aws_ecr_repository.service["notification-service"].repository_url}:${var.image_tag}"
      health_path    = "/health/liveness"
      health_command = "curl -fsS http://localhost:8084/health/liveness >/dev/null || exit 1"
      priority       = 40
      paths          = ["/api/v1/notifications"]
      environment = {
        NOTIFICATION_DB_URL          = "jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?currentSchema=notifications&sslmode=require"
        NOTIFICATION_DB_USERNAME     = aws_db_instance.main.username
        MICRONAUT_ENVIRONMENTS       = "aws"
        KAFKA_BOOTSTRAP              = "kafka:9092"
        NOTIFICATION_JWT_ISSUER_URI  = local.keycloak_issuer
        NOTIFICATION_JWT_JWK_SET_URI = "${local.keycloak_internal}/certs"
      }
    }
  }
}

resource "aws_ecs_task_definition" "application" {
  for_each                 = local.applications
  family                   = "${var.project_name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  # Rollback targets earlier revisions, and the deploy helper renders from the newest one.
  skip_destroy = true
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name         = each.key
    image        = each.value.image
    essential    = true
    cpu          = 256
    memory       = each.value.memory
    stopTimeout  = 30
    portMappings = [{ name = "${each.value.alias}-http", containerPort = each.value.port, hostPort = each.value.port, protocol = "tcp", appProtocol = "http" }]
    environment  = [for name, value in each.value.environment : { name = name, value = value }]
    secrets = concat(
      [{ name = "${each.value.db_prefix}_DB_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn }],
      [for name, key in lookup(each.value, "keycloak_secrets", {}) : { name = name, valueFrom = aws_ssm_parameter.keycloak[key].arn }]
    )
    healthCheck = { command = ["CMD-SHELL", each.value.health_command], interval = 10, timeout = 5, retries = 3, startPeriod = 60 }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.runtime[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "application" {
  for_each             = local.applications
  name                 = "${var.project_name}-${each.key}"
  cluster              = aws_ecs_cluster.main.id
  task_definition      = aws_ecs_task_definition.application[each.key].arn
  desired_count        = var.runtime_enabled ? 1 : 0
  force_new_deployment = var.runtime_enabled
  launch_type          = "FARGATE"
  platform_version     = "1.4.0"
  network_configuration {
    subnets          = sort(data.aws_subnets.default.ids)
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.main.arn
    service {
      port_name             = "${each.value.alias}-http"
      discovery_name        = each.value.alias
      ingress_port_override = each.value.proxy_port
      client_alias {
        dns_name = each.value.alias
        port     = each.value.port
      }
    }
    log_configuration {
      log_driver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service_connect.name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }

  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  load_balancer {
    target_group_arn = aws_lb_target_group.application[each.key].arn
    container_name   = each.key
    container_port   = each.value.port
  }
  lifecycle { ignore_changes = [task_definition] }
  depends_on = [aws_lb_listener_rule.application, aws_iam_role_policy.execution, aws_ecs_service.infrastructure, aws_ecs_service.keycloak]
}
