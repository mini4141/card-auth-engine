resource "aws_ecr_repository" "svc" {
  for_each             = toset(["auth-service", "settlement-service", "notification-service"])
  name                 = "${var.name}/${each.key}"
  force_delete         = true
  image_tag_mutability = "MUTABLE"
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = aws_ecr_repository.svc
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = 1
}

resource "aws_ecs_cluster" "main" {
  name = "${var.name}-cluster"
}

locals {
  kafka_env = {
    KAFKA_BOOTSTRAP                                            = aws_msk_serverless_cluster.kafka.bootstrap_brokers_sasl_iam
    KAFKA_PARTITIONS                                           = "3"
    KAFKA_REPLICAS                                             = "3"
    SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL                  = "SASL_SSL"
    SPRING_KAFKA_PROPERTIES_SASL_MECHANISM                     = "AWS_MSK_IAM"
    SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG                   = "software.amazon.msk.auth.iam.IAMLoginModule required;"
    SPRING_KAFKA_PROPERTIES_SASL_CLIENT_CALLBACK_HANDLER_CLASS = "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
  }
  db_env = {
    POSTGRES_HOST     = aws_db_instance.postgres.address
    POSTGRES_DB       = "cardauth"
    POSTGRES_USER     = "cardauth"
    POSTGRES_PASSWORD = random_password.db.result
    REDIS_HOST        = aws_elasticache_cluster.redis.cache_nodes[0].address
  }

  services = {
    "auth-service" = {
      cpu = 512, memory = 1024, port = 8080, count = var.auth_desired_count
      env = merge(local.kafka_env, local.db_env, {
        AWS_REGION                 = var.region
        DYNAMODB_TABLE             = aws_dynamodb_table.auth_log.name
        DYNAMODB_CREATE_TABLE      = "false"
        CLOUDWATCH_METRICS_ENABLED = "true"
        DEFAULT_STRATEGY           = var.default_strategy
      })
    }
    "settlement-service" = {
      cpu = 256, memory = 512, port = 8081, count = 1
      env = merge(local.kafka_env, local.db_env, { AWS_REGION = var.region })
    }
    "notification-service" = {
      cpu = 256, memory = 512, port = 8082, count = 1
      env = merge(local.kafka_env, { AWS_REGION = var.region })
    }
  }
}

resource "aws_ecs_task_definition" "svc" {
  for_each                 = local.services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = each.key
    image        = "${aws_ecr_repository.svc[each.key].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = each.value.port }]
    environment  = [for k, v in each.value.env : { name = k, value = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.svc[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "svc" {
  for_each                           = local.services
  name                               = each.key
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.svc[each.key].arn
  desired_count                      = each.value.count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = each.key == "settlement-service" ? null : 180
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 200
  wait_for_steady_state              = false

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }

  dynamic "load_balancer" {
    for_each = each.key == "settlement-service" ? [] : [1]
    content {
      target_group_arn = each.key == "auth-service" ? aws_lb_target_group.auth.arn : aws_lb_target_group.notification.arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  depends_on = [aws_lb_listener.http]
}
