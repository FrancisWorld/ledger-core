resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

# The master password is generated and stored by RDS in Secrets Manager, so it never appears in
# configuration or in the state file.
resource "aws_db_instance" "this" {
  identifier = var.name

  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "ledger"
  username = "ledger"

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = var.db_multi_az

  backup_retention_period   = 7
  copy_tags_to_snapshot     = true
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name}-final"

  auto_minor_version_upgrade = true
  apply_immediately          = false
}

# Credentials of the unprivileged ledger_app role the service connects as. The role itself is
# created by Flyway migration V6 (run by the migrate task as the owner), which receives this
# password as a placeholder. Unlike the RDS-managed master secret, this password is generated here
# and therefore also lives in the OpenTofu state: keep the state in an encrypted backend.
resource "random_password" "runtime" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "runtime_db" {
  name                    = "${var.name}/runtime-db"
  description             = "Credentials of the ledger_app database role"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "runtime_db" {
  secret_id = aws_secretsmanager_secret.runtime_db.id

  secret_string = jsonencode({
    username = "ledger_app"
    password = random_password.runtime.result
  })
}
