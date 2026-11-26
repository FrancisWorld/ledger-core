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
