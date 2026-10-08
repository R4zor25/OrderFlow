locals {
  infrastructure = {
    kafka = {
      alias          = "kafka"
      port           = 9092
      proxy_port     = 19092
      memory         = 768
      image          = "apache/kafka:4.3.1"
      health_command = "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"
      command        = null
      environment = {
        KAFKA_NODE_ID                                          = "1"
        KAFKA_PROCESS_ROLES                                    = "broker,controller"
        KAFKA_CONTROLLER_QUORUM_VOTERS                         = "1@localhost:9093"
        KAFKA_LISTENERS                                        = "PLAINTEXT://:9092,CONTROLLER://:9093"
        KAFKA_ADVERTISED_LISTENERS                             = "PLAINTEXT://kafka:9092"
        KAFKA_LISTENER_SECURITY_PROTOCOL_MAP                   = "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT"
        KAFKA_INTER_BROKER_LISTENER_NAME                       = "PLAINTEXT"
        KAFKA_CONTROLLER_LISTENER_NAMES                        = "CONTROLLER"
        KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR                 = "1"
        KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR         = "1"
        KAFKA_TRANSACTION_STATE_LOG_MIN_ISR                    = "1"
        KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR = "1"
        KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR            = "1"
        KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS                 = "0"
        KAFKA_AUTO_CREATE_TOPICS_ENABLE                        = "false"
        KAFKA_LOG_DIRS                                         = "/var/lib/kafka/data"
        CLUSTER_ID                                             = "lqYrP7tgRdeTHa706V-ucw"
        KAFKA_HEAP_OPTS                                        = "-Xms256m -Xmx512m"
      }
    }
    # volatile-lru evicts only expiring keys; every key has a TTL, so a write never hits the memory limit.
    # No snapshots: the durable idempotency record is in PostgreSQL, and a background save would fork Redis.
    redis = {
      alias          = "redis"
      port           = 6379
      proxy_port     = 16379
      memory         = 384
      image          = "redis:7.4.11-bookworm"
      health_command = "redis-cli ping | grep -q PONG"
      command        = ["redis-server", "--save", "", "--maxmemory", "256mb", "--maxmemory-policy", "volatile-lru"]
      environment    = {}
    }
  }
  kafka_topics         = ["orders.order-events.v1", "inventory.stock-events.v1", "orders.order-events.v1.inventory-service.DLT", "inventory.stock-events.v1.order-service.DLT", "orders.order-events.v1.notification-service.DLT", "inventory.stock-events.v1.notification-service.DLT"]
  kafka_topics_command = "set -e; for topic in ${join(" ", local.kafka_topics)}; do /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $topic --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config segment.ms=86400000; /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --alter --entity-type topics --entity-name $topic --add-config retention.ms=604800000,segment.ms=86400000; done"
  # Auto-creation is off, so a replaced broker needs its topics before the applications restart.
  kafka_topics_container = {
    name        = "kafka-topics"
    image       = local.infrastructure.kafka.image
    essential   = false
    cpu         = 128
    memory      = 192
    entryPoint  = ["sh", "-c"]
    command     = [local.kafka_topics_command]
    environment = [{ name = "KAFKA_HEAP_OPTS", value = "-Xmx128m" }]
    dependsOn   = [{ containerName = "kafka", condition = "HEALTHY" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.runtime["kafka"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "topics"
      }
    }
  }
}

resource "aws_ecs_task_definition" "infrastructure" {
  for_each                 = local.infrastructure
  family                   = "${var.project_name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode(concat([{
    name         = each.key
    image        = each.value.image
    command      = each.value.command
    essential    = true
    cpu          = 256
    memory       = each.value.memory
    stopTimeout  = 30
    portMappings = [{ name = "${each.value.alias}-tcp", containerPort = each.value.port, hostPort = each.value.port, protocol = "tcp" }]
    environment  = [for name, value in each.value.environment : { name = name, value = value }]

    healthCheck = { command = ["CMD-SHELL", each.value.health_command], interval = 10, timeout = 10, retries = 10, startPeriod = 60 }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.runtime[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }], [for c in [local.kafka_topics_container] : c if each.key == "kafka"]))
}

resource "aws_ecs_service" "infrastructure" {
  for_each             = local.infrastructure
  name                 = "${var.project_name}-${each.key}"
  cluster              = aws_ecs_cluster.main.id
  task_definition      = aws_ecs_task_definition.infrastructure[each.key].arn
  desired_count        = var.runtime_enabled ? 1 : 0
  force_new_deployment = var.runtime_enabled
  launch_type          = "FARGATE"
  platform_version     = "1.4.0"
  # Two brokers or Redis instances behind one alias would split events and idempotency keys.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
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
      port_name             = "${each.value.alias}-tcp"
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
  depends_on = [aws_iam_role_policy.execution]
}
