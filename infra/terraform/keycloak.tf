locals {
  keycloak_parameters = {
    admin_username = "KC_BOOTSTRAP_ADMIN_USERNAME"
    admin_password = "KC_BOOTSTRAP_ADMIN_PASSWORD"
    client_secret  = "ORDER_SERVICE_CLIENT_SECRET"
    demo_password  = "DEMO_USER_PASSWORD"
  }
  keycloak_issuer   = "http://${aws_lb.main.dns_name}/realms/orderflow"
  keycloak_internal = "http://keycloak:8080/realms/orderflow/protocol/openid-connect"
}

resource "random_password" "keycloak" {
  for_each = toset(["admin_password", "client_secret", "demo_password"])
  length   = 32
  special  = false
}

resource "aws_ssm_parameter" "keycloak" {
  for_each    = local.keycloak_parameters
  name        = "/${var.project_name}/keycloak/${each.key}"
  description = "Keycloak ${each.key} for the demo realm."
  type        = "SecureString"
  value       = each.key == "admin_username" ? "orderflow-admin" : random_password.keycloak[each.key].result
}

resource "aws_cloudwatch_log_group" "keycloak" {
  name              = "/ecs/${var.project_name}/keycloak"
  retention_in_days = 3
}

resource "aws_ecs_task_definition" "keycloak" {
  family                   = "${var.project_name}-keycloak"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name        = "keycloak"
    image       = "${aws_ecr_repository.service["keycloak"].repository_url}:${var.image_tag}"
    essential   = true
    cpu         = 512
    memory      = 1536
    stopTimeout = 30
    portMappings = [
      { name = "keycloak-http", containerPort = 8080, hostPort = 8080, protocol = "tcp", appProtocol = "http" },
      { name = "keycloak-management", containerPort = 9000, hostPort = 9000, protocol = "tcp" }
    ]
    # No KC_PROXY_HEADERS: trusting X-Forwarded-For would make every ALB caller "external" and
    # trigger the realm's HTTPS requirement on this HTTP-only demo; KC_HOSTNAME already fixes the URLs.
    environment = [for name, value in {
      KC_DB_URL                       = "jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?sslmode=require"
      KC_DB_USERNAME                  = aws_db_instance.main.username
      KC_HTTP_ENABLED                 = "true"
      KC_HOSTNAME                     = "http://${aws_lb.main.dns_name}"
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC = "true"
    } : { name = name, value = value }]
    secrets = concat(
      [{ name = "KC_DB_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn }],
      [for key, variable in local.keycloak_parameters : { name = variable, valueFrom = aws_ssm_parameter.keycloak[key].arn }]
    )
    # The Keycloak image ships without curl or wget; bash reads the management endpoint directly.
    healthCheck = {
      command     = ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\": \"UP\"' <&3"]
      interval    = 10
      timeout     = 5
      retries     = 5
      startPeriod = 120
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.keycloak.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_lb_target_group" "keycloak" {
  name_prefix          = "kc-"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = data.aws_vpc.default.id
  deregistration_delay = 30
  health_check {
    port                = "9000"
    path                = "/health/ready"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener_rule" "keycloak" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 5
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.keycloak.arn
  }
  condition {
    path_pattern { values = ["/realms/*", "/admin/*", "/resources/*"] }
  }
}

resource "aws_vpc_security_group_ingress_rule" "keycloak_from_alb" {
  for_each                     = toset(["8080", "9000"])
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
}

resource "aws_vpc_security_group_egress_rule" "alb_keycloak" {
  for_each                     = toset(["8080", "9000"])
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
}

resource "aws_vpc_security_group_ingress_rule" "keycloak_service_connect" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 18180
  to_port                      = 18180
}

resource "aws_ecs_service" "keycloak" {
  name                 = "${var.project_name}-keycloak"
  cluster              = aws_ecs_cluster.main.id
  task_definition      = aws_ecs_task_definition.keycloak.arn
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
  # Two Keycloak tasks would race on the shared schema at startup, so the old task stops first.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  health_check_grace_period_seconds  = 180
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.main.arn
    service {
      port_name             = "keycloak-http"
      discovery_name        = "keycloak"
      ingress_port_override = 18180
      client_alias {
        dns_name = "keycloak"
        port     = 8080
      }
    }
    log_configuration {
      log_driver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service_connect.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "keycloak"
      }
    }
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.keycloak.arn
    container_name   = "keycloak"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener_rule.keycloak, aws_iam_role_policy.execution]
}
