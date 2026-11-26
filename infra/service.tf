resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name}"
  retention_in_days = 30
}

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # The root filesystem is read-only; the JVM and Tomcat need a scratch directory.
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name                   = "ledger"
      image                  = var.container_image
      essential              = true
      readonlyRootFilesystem = true
      stopTimeout            = 30

      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      mountPoints  = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]

      environment = [
        { name = "LEDGER_DB_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}" },
        { name = "LEDGER_OUTBOX_SINK", value = "SQS" },
        { name = "LEDGER_OUTBOX_SQS_QUEUE_URL", value = aws_sqs_queue.events.url },
        { name = "LEDGER_OUTBOX_SQS_REGION", value = var.region },
        # schema changes are applied by the migrate task, never by the service
        { name = "SPRING_FLYWAY_ENABLED", value = "false" },
      ]

      secrets = [
        { name = "LEDGER_DB_USER", valueFrom = "${aws_secretsmanager_secret.runtime_db.arn}:username::" },
        { name = "LEDGER_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime_db.arn}:password::" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "ledger"
        }
      }
    }
  ])
}

# One-off task, not a service. It applies Flyway migrations as the schema owner and exits; run it
# with the same image before rolling out a new version, or override LEDGER_MIGRATE_ONLY and set
# LEDGER_ADMIN_ISSUE_KEY_NAME to issue an API key (the key is printed once to the task's log).
resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.name}-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.migrate_execution.arn

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name                   = "migrate"
      image                  = var.container_image
      essential              = true
      readonlyRootFilesystem = true
      mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]

      environment = [
        { name = "LEDGER_DB_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}" },
        { name = "LEDGER_MIGRATE_ONLY", value = "true" },
        { name = "SPRING_MAIN_WEB_APPLICATION_TYPE", value = "none" },
      ]

      secrets = [
        { name = "LEDGER_DB_USER", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:username::" },
        { name = "LEDGER_DB_PASSWORD", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::" },
        { name = "LEDGER_MIGRATION_DB_USER", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:username::" },
        { name = "LEDGER_MIGRATION_DB_PASSWORD", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::" },
        { name = "LEDGER_RUNTIME_ROLE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime_db.arn}:password::" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "migrate"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  health_check_grace_period_seconds = 60

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "ledger"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener.https]
}

resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this.name}"
  min_capacity       = var.desired_count
  max_capacity       = var.desired_count * 4
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
