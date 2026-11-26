output "alb_dns_name" {
  description = "Public DNS name of the load balancer."
  value       = aws_lb.this.dns_name
}

output "ecr_repository_url" {
  description = "Where to push the container image."
  value       = aws_ecr_repository.this.repository_url
}

output "events_queue_url" {
  description = "Queue the outbox relay publishes to."
  value       = aws_sqs_queue.events.url
}

output "events_dlq_url" {
  description = "Dead-letter queue for events consumers could not process."
  value       = aws_sqs_queue.events_dlq.url
}

output "database_endpoint" {
  description = "PostgreSQL endpoint (reachable from the private subnets only)."
  value       = aws_db_instance.this.address
}

output "database_secret_arn" {
  description = "Secrets Manager secret holding the generated database credentials."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "migrate_task_definition" {
  description = "Run this one-off task (with the private subnets and app security group) before each rollout to apply migrations."
  value       = aws_ecs_task_definition.migrate.arn
}

output "runtime_db_secret_arn" {
  description = "Secret read by the service (the unprivileged ledger_app role)."
  value       = aws_secretsmanager_secret.runtime_db.arn
}

output "private_subnet_ids" {
  description = "Subnets for the migrate task."
  value       = aws_subnet.private[*].id
}

output "app_security_group_id" {
  description = "Security group for the migrate task."
  value       = aws_security_group.app.id
}
