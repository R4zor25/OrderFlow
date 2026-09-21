mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_data "aws_partition" { defaults = { partition = "aws" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/orderflow-runtime" } }
  mock_resource "aws_lb_target_group" { defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:targetgroup/demo/1234567890123456" } }
  mock_resource "aws_lb" { defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:loadbalancer/app/demo/1234567890123456" } }
  mock_resource "aws_lb_listener" { defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:listener/app/demo/1234567890123456/1234567890123456" } }
  mock_resource "aws_service_discovery_http_namespace" { defaults = { arn = "arn:aws:servicediscovery:eu-central-1:123456789012:namespace/ns-demo" } }
  mock_resource "aws_ecs_task_definition" { defaults = { arn = "arn:aws:ecs:eu-central-1:123456789012:task-definition/demo:1" } }

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
variables {
  image_tag             = "66dbedbd4866e4e2ba794c10895d27e811511d72"
  github_repository     = "test-org/orderflow"
  github_owner_id       = "100"
  github_repository_id  = "200"
  allowed_ingress_cidrs = ["203.0.113.10/32"]
}
run "bootstrap_runtime" {
  command = apply
  assert {
    condition     = (length(aws_ecs_service.application) == 4 && length(aws_ecs_service.infrastructure) == 2)
    error_message = "Six independent services are required."
  }
  assert {
    condition     = (alltrue([for s in merge(aws_ecs_service.application, aws_ecs_service.infrastructure) : s.desired_count == 0 && s.force_new_deployment == false]))
    error_message = "Bootstrap must not start tasks or force an early deployment."
  }
  assert {
    condition     = (alltrue([for t in merge(aws_ecs_task_definition.application, aws_ecs_task_definition.infrastructure) : t.cpu == "512" && t.memory == "1024" && t.network_mode == "awsvpc" && t.runtime_platform[0].cpu_architecture == "ARM64" && contains(t.requires_compatibilities, "FARGATE") && jsondecode(t.container_definitions)[0].cpu == 256 && jsondecode(t.container_definitions)[0].memory < tonumber(t.memory)]))
    error_message = "Tasks need ARM64 Fargate and proxy headroom."
  }
  assert {
    condition     = (alltrue([for name, s in merge(aws_ecs_service.application, aws_ecs_service.infrastructure) : s.service_connect_configuration[0].enabled && s.service_connect_configuration[0].service[0].ingress_port_override == lookup({ catalog-pricing-service = 18081, order-service = 18082, inventory-service = 18083, notification-service = 18084, kafka = 19092, redis = 16379 }, name) && s.service_connect_configuration[0].service[0].client_alias[0].port == lookup({ catalog-pricing-service = 8081, order-service = 8082, inventory-service = 8083, notification-service = 8084, kafka = 9092, redis = 6379 }, name) && s.service_connect_configuration[0].service[0].client_alias[0].dns_name == lookup({ catalog-pricing-service = "catalog-pricing", order-service = "order", inventory-service = "inventory", notification-service = "notification", kafka = "kafka", redis = "redis" }, name)]))
    error_message = "Proxy ingress and client aliases must use distinct correct ports."
  }
  assert {
    condition     = (alltrue([for name, t in aws_ecs_task_definition.application : length(jsondecode(t.container_definitions)[0].secrets) == (name == "order-service" ? 2 : 1) && jsondecode(t.container_definitions)[0].secrets[0].valueFrom == aws_ssm_parameter.db_password.arn && jsondecode(t.container_definitions)[0].secrets[0].name == "${lookup({ catalog-pricing-service = "CATALOG", order-service = "ORDER", inventory-service = "INVENTORY", notification-service = "NOTIFICATION" }, name)}_DB_PASSWORD" && alltrue([for e in jsondecode(t.container_definitions)[0].environment : !endswith(e.name, "PASSWORD")])]))
    error_message = "Database passwords must only be injected from SSM secrets."
  }
  assert {
    condition     = alltrue([for t in aws_ecs_task_definition.application : t.skip_destroy])
    error_message = "Application task definitions must keep old revisions ACTIVE for rollback."
  }
  assert {
    condition     = (alltrue([for name, t in aws_ecs_task_definition.application : jsondecode(t.container_definitions)[0].image == "${aws_ecr_repository.service[name].repository_url}:${var.image_tag}" && jsondecode(t.container_definitions)[0].stopTimeout == 30 && jsondecode(t.container_definitions)[0].memory == (name == "notification-service" ? 512 : 768) && strcontains(jsondecode(t.container_definitions)[0].healthCheck.command[1], "/health/liveness")]))
    error_message = "Application image SHA, memory and liveness must match runtime contracts."
  }
  assert {
    condition     = (alltrue([for s in aws_ecs_service.application : s.health_check_grace_period_seconds == 90 && s.deployment_minimum_healthy_percent == 100 && s.deployment_maximum_percent == 200 && s.deployment_circuit_breaker[0].enable && s.deployment_circuit_breaker[0].rollback]))
    error_message = "Application deployments need health grace and rollback."
  }
  assert {
    condition     = (alltrue([for s in merge(aws_ecs_service.application, aws_ecs_service.infrastructure) : s.network_configuration[0].assign_public_ip && s.network_configuration[0].security_groups == toset([aws_security_group.tasks.id]) && s.launch_type == "FARGATE" && s.platform_version == "1.4.0"]))
    error_message = "Demo task networking must be explicit."
  }
  assert {
    condition     = (alltrue([for name, g in aws_lb_target_group.application : g.target_type == "ip" && g.port == lookup({ catalog-pricing-service = 8081, order-service = 8082, inventory-service = 8083, notification-service = 8084 }, name) && g.health_check[0].path == (name == "notification-service" ? "/health/readiness" : "/actuator/health/readiness") && g.health_check[0].matcher == "200" && g.deregistration_delay == "30"]))
    error_message = "ALB must route only to ready IP targets."
  }
  assert {
    condition     = (alltrue([for g in aws_cloudwatch_log_group.runtime : g.retention_in_days == 3]) && aws_cloudwatch_log_group.service_connect.retention_in_days == 3)
    error_message = "Log retention must bound demo storage."
  }
  assert {
    condition     = (length(jsondecode(aws_iam_role_policy.execution.policy).Statement) == 4 && jsondecode(aws_iam_role_policy.execution.policy).Statement[3].Action == ["ssm:GetParameters"] && toset(jsondecode(aws_iam_role_policy.execution.policy).Statement[3].Resource) == toset(concat([aws_ssm_parameter.db_password.arn], [for p in aws_ssm_parameter.keycloak : p.arn])))
    error_message = "Execution role must read only the database and Keycloak parameters."
  }
  assert {
    condition     = (alltrue([for name, rule in aws_vpc_security_group_ingress_rule.service_connect : rule.from_port == lookup({ catalog-pricing-service = 18081, order-service = 18082, inventory-service = 18083, notification-service = 18084, kafka = 19092, redis = 16379 }, name) && rule.to_port == rule.from_port && rule.referenced_security_group_id == aws_security_group.tasks.id && rule.security_group_id == aws_security_group.tasks.id]))
    error_message = "Internal ingress must allow only Service Connect proxy ports."
  }
  assert {
    condition     = (aws_lb_listener.http.default_action[0].fixed_response[0].status_code == "404" && aws_lb_listener.http.port == 80 && !aws_lb.main.internal)
    error_message = "ALB must expose HTTP with a default 404."
  }
  assert {
    condition = (alltrue([for name, t in aws_ecs_task_definition.application :
      { for e in jsondecode(t.container_definitions)[0].environment : e.name => e.value }["${lookup({ catalog-pricing-service = "CATALOG", order-service = "ORDER", inventory-service = "INVENTORY", notification-service = "NOTIFICATION" }, name)}_DB_URL"] == "jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?currentSchema=${lookup({ catalog-pricing-service = "catalog", order-service = "orders", inventory-service = "inventory", notification-service = "notifications" }, name)}&sslmode=require" &&
      { for e in jsondecode(t.container_definitions)[0].environment : e.name => e.value }["${lookup({ catalog-pricing-service = "CATALOG", order-service = "ORDER", inventory-service = "INVENTORY", notification-service = "NOTIFICATION" }, name)}_DB_USERNAME"] == aws_db_instance.main.username &&
      jsondecode(t.container_definitions)[0].portMappings[0].containerPort == lookup({ catalog-pricing-service = 8081, order-service = 8082, inventory-service = 8083, notification-service = 8084 }, name) &&
      jsondecode(t.container_definitions)[0].portMappings[0].appProtocol == "http"
    ]))
    error_message = "Application tasks must wire the correct DB schema and HTTP port."
  }
  assert {
    condition = (alltrue([for name, expected in {
      catalog-pricing-service = { SPRING_PROFILES_ACTIVE = "aws", CATALOG_REDIS_HOST = "redis" }
      order-service           = { SPRING_PROFILES_ACTIVE = "aws", ORDER_REDIS_HOST = "redis", KAFKA_BOOTSTRAP = "kafka:9092", CATALOG_PRICING_BASE_URL = "http://catalog-pricing:8081" }
      inventory-service       = { SPRING_PROFILES_ACTIVE = "aws", KAFKA_BOOTSTRAP = "kafka:9092", INVENTORY_OUTBOX_RELAY_ENABLED = "true" }
      notification-service    = { MICRONAUT_ENVIRONMENTS = "aws", KAFKA_BOOTSTRAP = "kafka:9092" }
    } : alltrue([for key, value in expected : { for e in jsondecode(aws_ecs_task_definition.application[name].container_definitions)[0].environment : e.name => e.value }[key] == value])]))
    error_message = "AWS profiles and internal dependency inputs must enable the whole business flow."
  }
  assert {
    condition = (alltrue([for name, t in aws_ecs_task_definition.infrastructure :
      jsondecode(t.container_definitions)[0].image == lookup({ kafka = "apache/kafka:4.3.1", redis = "redis:7.4.11-bookworm" }, name) &&
      jsondecode(t.container_definitions)[0].memory == (name == "kafka" ? 768 : 384) &&
      !contains(keys(jsondecode(t.container_definitions)[0].portMappings[0]), "appProtocol") &&
      length(t.volume) == 0
      ]) && { for e in jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[0].environment : e.name => e.value }["KAFKA_ADVERTISED_LISTENERS"] == "PLAINTEXT://kafka:9092" &&
      { for e in jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[0].environment : e.name => e.value }["KAFKA_CONTROLLER_QUORUM_VOTERS"] == "1@localhost:9093" &&
    { for e in jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[0].environment : e.name => e.value }["KAFKA_HEAP_OPTS"] == "-Xms256m -Xmx512m")
    error_message = "Infrastructure must use the pinned TCP images and the single-node ephemeral Kafka contract."
  }
  assert {
    condition = (alltrue([for s in aws_ecs_service.infrastructure : s.deployment_minimum_healthy_percent == 0 && s.deployment_maximum_percent == 100])
    && alltrue([for s in aws_ecs_service.application : s.deployment_minimum_healthy_percent == 100 && s.deployment_maximum_percent == 200]))
    error_message = "Kafka and Redis must redeploy without two instances serving the same alias."
  }
  assert {
    condition = (jsondecode(aws_ecs_task_definition.infrastructure["redis"].container_definitions)[0].command == ["redis-server", "--maxmemory", "256mb", "--maxmemory-policy", "volatile-lru"]
    && lookup(jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[0], "command", null) == null)
    error_message = "Redis must be memory-bounded with volatile-lru; the Kafka broker keeps its image command."
  }
  assert {
    condition = (length(jsondecode(aws_ecs_task_definition.infrastructure["redis"].container_definitions)) == 1 &&
      length(jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)) == 2 &&
      jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].name == "kafka-topics" &&
      jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].essential == false &&
      jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].dependsOn[0].containerName == "kafka" &&
      jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].dependsOn[0].condition == "HEALTHY" &&
      jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].image == jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[0].image &&
      alltrue([for topic in ["orders.order-events.v1", "orders.order-events.v1.DLT", "inventory.stock-events.v1", "inventory.stock-events.v1.DLT"] :
      strcontains(jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].command[0], topic)]) &&
      strcontains(jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].command[0], "--if-not-exists") &&
      strcontains(jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].command[0], "--partitions 3") &&
    jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[0].memory + jsondecode(aws_ecs_task_definition.infrastructure["kafka"].container_definitions)[1].memory < 1024)
    error_message = "The Kafka task must create every business and DLT topic after the broker is healthy."
  }
  assert {
    condition = (alltrue([for name, rule in aws_lb_listener_rule.application :
      rule.priority == lookup({ catalog-pricing-service = 10, order-service = 20, inventory-service = 30, notification-service = 40 }, name) &&
      rule.action[0].target_group_arn == aws_lb_target_group.application[name].arn &&
      one(rule.condition).path_pattern[0].values == toset(lookup({ catalog-pricing-service = ["/api/v1/products", "/api/v1/products/*"], order-service = ["/api/v1/orders", "/api/v1/orders/*"], inventory-service = ["/api/v1/stock", "/api/v1/stock/*", "/api/v1/reservations"], notification-service = ["/api/v1/notifications"] }, name))
    ]))
    error_message = "Public API paths must map to the owning service without rewriting."
  }
  assert {
    condition = (alltrue([for name, s in aws_ecs_service.application :
      one(s.load_balancer).container_name == name &&
      one(s.load_balancer).container_port == aws_lb_target_group.application[name].port &&
      one(s.load_balancer).target_group_arn == aws_lb_target_group.application[name].arn
      ]) && alltrue([for name, rule in aws_vpc_security_group_ingress_rule.application :
      rule.from_port == lookup({ catalog-pricing-service = 8081, order-service = 8082, inventory-service = 8083, notification-service = 8084 }, name) && rule.to_port == rule.from_port &&
      rule.security_group_id == aws_security_group.tasks.id && rule.referenced_security_group_id == aws_security_group.alb.id && rule.cidr_ipv4 == null
      ]) && length(aws_vpc_security_group_ingress_rule.alb_http) == 1 && alltrue([for rule in aws_vpc_security_group_ingress_rule.alb_http :
      rule.security_group_id == aws_security_group.alb.id && rule.cidr_ipv4 == "203.0.113.10/32" && rule.ip_protocol == "tcp" && rule.from_port == 80 && rule.to_port == 80
    ]))
    error_message = "Only the ALB may reach application ports from outside the task group."
  }
  assert {
    condition = (jsondecode(aws_iam_role_policy.execution.policy).Statement[0].Action == ["ecr:GetAuthorizationToken"] &&
      jsondecode(aws_iam_role_policy.execution.policy).Statement[0].Resource == ["*"] &&
      toset(jsondecode(aws_iam_role_policy.execution.policy).Statement[1].Resource) == toset([for repo in aws_ecr_repository.service : repo.arn]) &&
      toset(jsondecode(aws_iam_role_policy.execution.policy).Statement[1].Action) == toset(["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]) &&
      toset(jsondecode(aws_iam_role_policy.execution.policy).Statement[2].Action) == toset(["logs:CreateLogStream", "logs:PutLogEvents"]) &&
      toset(jsondecode(aws_iam_role_policy.execution.policy).Statement[2].Resource) == toset(concat([for g in aws_cloudwatch_log_group.runtime : "${g.arn}:*"], ["${aws_cloudwatch_log_group.service_connect.arn}:*", "${aws_cloudwatch_log_group.keycloak.arn}:*"])) &&
      jsondecode(aws_iam_role.execution.assume_role_policy).Statement[0].Principal.Service == "ecs-tasks.amazonaws.com" &&
    jsondecode(aws_iam_role.task.assume_role_policy).Statement[0].Principal.Service == "ecs-tasks.amazonaws.com")
    error_message = "Execution permissions must be limited to image pulls, log streams and secret injection."
  }
  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].image == "${aws_ecr_repository.service["keycloak"].repository_url}:${var.image_tag}" &&
      aws_ecs_task_definition.keycloak.cpu == "1024" && aws_ecs_task_definition.keycloak.memory == "2048" &&
      jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].memory == 1536 &&
      contains([for e in jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].environment : "${e.name}=${e.value}"], "KC_HTTP_ENABLED=true") &&
      contains([for e in jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].environment : "${e.name}=${e.value}"], "KC_HOSTNAME=http://${aws_lb.main.dns_name}") &&
      contains([for e in jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].environment : "${e.name}=${e.value}"], "KC_DB_URL=jdbc:postgresql://${aws_db_instance.main.endpoint}/orderflow?sslmode=require") &&
      !contains([for e in jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].environment : e.name], "KC_DB_SCHEMA") &&
      !contains([for e in jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].environment : e.name], "KC_PROXY_HEADERS") &&
      toset([for s in jsondecode(aws_ecs_task_definition.keycloak.container_definitions)[0].secrets : s.name]) ==
      toset(["KC_DB_PASSWORD", "KC_BOOTSTRAP_ADMIN_USERNAME", "KC_BOOTSTRAP_ADMIN_PASSWORD", "ORDER_SERVICE_CLIENT_SECRET", "DEMO_USER_PASSWORD"])
    )
    error_message = "Keycloak must run the release image in production mode against RDS with SSM secrets."
  }
  assert {
    condition = (
      aws_lb_listener_rule.keycloak.priority == 5 &&
      one(aws_lb_listener_rule.keycloak.condition).path_pattern[0].values == toset(["/realms/*", "/admin/*", "/resources/*"]) &&
      aws_lb_listener_rule.keycloak.action[0].target_group_arn == aws_lb_target_group.keycloak.arn &&
      aws_lb_target_group.keycloak.port == 8080 &&
      aws_lb_target_group.keycloak.health_check[0].port == "9000" &&
      aws_lb_target_group.keycloak.health_check[0].path == "/health/ready"
    )
    error_message = "Only Keycloak paths may route to Keycloak, with readiness checked on the management port."
  }
  assert {
    condition = (
      aws_ecs_service.keycloak.desired_count == 0 &&
      aws_ecs_service.keycloak.deployment_minimum_healthy_percent == 0 &&
      aws_ecs_service.keycloak.deployment_maximum_percent == 100 &&
      one(one(aws_ecs_service.keycloak.service_connect_configuration).service).discovery_name == "keycloak" &&
      one(one(aws_ecs_service.keycloak.service_connect_configuration).service).ingress_port_override == 18180 &&
      one(one(one(aws_ecs_service.keycloak.service_connect_configuration).service).client_alias).port == 8080 &&
      one(aws_ecs_service.keycloak.load_balancer).container_port == 8080
    )
    error_message = "One Keycloak task at a time, reachable as keycloak:8080 inside the namespace and behind the ALB."
  }
  assert {
    condition = (
      toset([for r in aws_vpc_security_group_ingress_rule.keycloak_from_alb : r.from_port]) == toset([8080, 9000]) &&
      alltrue([for r in aws_vpc_security_group_ingress_rule.keycloak_from_alb : r.referenced_security_group_id == aws_security_group.alb.id && r.security_group_id == aws_security_group.tasks.id && r.cidr_ipv4 == null]) &&
      toset([for r in aws_vpc_security_group_egress_rule.alb_keycloak : r.from_port]) == toset([8080, 9000]) &&
      aws_vpc_security_group_ingress_rule.keycloak_service_connect.from_port == 18180 &&
      aws_vpc_security_group_ingress_rule.keycloak_service_connect.referenced_security_group_id == aws_security_group.tasks.id
    )
    error_message = "Keycloak ports must be reachable only from the ALB and its proxy port only from tasks."
  }
  assert {
    condition     = length(aws_ssm_parameter.keycloak) == 4 && alltrue([for p in aws_ssm_parameter.keycloak : p.type == "SecureString"])
    error_message = "Keycloak secrets must be SecureString parameters."
  }
  assert {
    condition     = toset(one([for s in jsondecode(aws_iam_role_policy.execution.policy).Statement : s.Resource if contains(s.Action, "ssm:GetParameters")])) == toset(concat([aws_ssm_parameter.db_password.arn], [for p in aws_ssm_parameter.keycloak : p.arn]))
    error_message = "The execution role may read exactly the database and Keycloak parameters."
  }
  assert {
    condition = alltrue([for name in ["catalog-pricing-service", "order-service"] :
      contains([for e in jsondecode(aws_ecs_task_definition.application[name].container_definitions)[0].environment : e.value], "http://${aws_lb.main.dns_name}/realms/orderflow") &&
      contains([for e in jsondecode(aws_ecs_task_definition.application[name].container_definitions)[0].environment : e.value], "http://keycloak:8080/realms/orderflow/protocol/openid-connect/certs")
    ]) && contains([for e in jsondecode(aws_ecs_task_definition.application["order-service"].container_definitions)[0].environment : "${e.name}=${e.value}"], "ORDER_TOKEN_URI=http://keycloak:8080/realms/orderflow/protocol/openid-connect/token") && contains([for s in jsondecode(aws_ecs_task_definition.application["order-service"].container_definitions)[0].secrets : "${s.name}=${s.valueFrom}"], "ORDER_SERVICE_CLIENT_SECRET=${aws_ssm_parameter.keycloak["client_secret"].arn}")
    error_message = "Catalog and order must trust the Keycloak issuer and fetch keys internally; order gets the client secret."
  }
  assert {
    condition = (output.cluster_name == aws_ecs_cluster.main.name && output.alb_dns_name == aws_lb.main.dns_name && !output.runtime_enabled && output.image_tag == var.image_tag &&
      toset(keys(output.service_names)) == toset(["catalog-pricing-service", "order-service", "inventory-service", "notification-service", "kafka", "redis"]) &&
    output.task_definition_arns == { for name, t in merge(aws_ecs_task_definition.application, aws_ecs_task_definition.infrastructure) : name => t.arn })
    error_message = "Deployment outputs must identify all runtime resources without activating bootstrap."
  }
}
run "active_runtime" {
  command = apply
  variables { runtime_enabled = true }
  assert {
    condition     = (alltrue([for s in merge(aws_ecs_service.application, aws_ecs_service.infrastructure, { keycloak = aws_ecs_service.keycloak }) : s.desired_count == 1 && s.force_new_deployment == true]))
    error_message = "Activation must start each service with a fresh endpoint deployment."
  }
}
run "reject_mutable_image" {
  command = plan
  variables { image_tag = "latest" }
  expect_failures = [var.image_tag]
}
