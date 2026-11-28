output "alb_dns_name" { value = aws_lb.main.dns_name }
output "ecs_cluster" { value = aws_ecs_cluster.main.name }
output "ecr_repositories" { value = { for k, r in aws_ecr_repository.svc : k => r.repository_url } }
output "rds_endpoint" { value = aws_db_instance.postgres.address }
output "redis_endpoint" { value = aws_elasticache_cluster.redis.cache_nodes[0].address }
output "dynamodb_table" { value = aws_dynamodb_table.auth_log.name }
output "msk_cluster_arn" { value = aws_msk_serverless_cluster.kafka.arn }
output "msk_bootstrap_brokers" { value = aws_msk_serverless_cluster.kafka.bootstrap_brokers_sasl_iam }
output "log_groups" { value = [for g in aws_cloudwatch_log_group.svc : g.name] }
