variable "name" {
  description = "Prefix for every resource name."
  type        = string
  default     = "ledger-core"
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "container_image" {
  description = "Image to run, e.g. the ECR repository URL with a tag. Push it before the first apply."
  type        = string
  default     = "ledger-core:latest"
}

variable "desired_count" {
  description = "Number of Fargate tasks."
  type        = number
  default     = 2
}

variable "task_cpu" {
  description = "Fargate CPU units."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate memory in MiB."
  type        = number
  default     = 1024
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_allocated_storage" {
  description = "Initial RDS storage in GiB."
  type        = number
  default     = 50
}

variable "db_multi_az" {
  description = "Run a synchronous standby in a second availability zone."
  type        = bool
  default     = true
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener. Required: the service is only exposed over HTTPS and port 80 only redirects."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:acm:", var.certificate_arn))
    error_message = "certificate_arn must be the ARN of an ACM certificate."
  }
}

variable "ingress_cidrs" {
  description = "CIDR blocks allowed to reach the load balancer. Empty by default, so nothing is reachable until you list your clients (or a CDN/WAF range) explicitly."
  type        = list(string)
  default     = []
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway between AZs (cheaper, less resilient)."
  type        = bool
  default     = true
}
