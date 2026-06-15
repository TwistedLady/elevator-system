# Single-node KRaft Kafka, reachable in-cluster at kafka:9092.
resource "kubernetes_deployment_v1" "kafka" {
  metadata {
    name   = "kafka"
    labels = { app = "kafka" }
  }
  spec {
    replicas = 1
    selector { match_labels = { app = "kafka" } }
    template {
      metadata { labels = { app = "kafka" } }
      spec {
        container {
          name  = "kafka"
          image = "apache/kafka:3.7.0"
          port { container_port = 9092 }

          dynamic "env" {
            for_each = {
              KAFKA_NODE_ID                                  = "1"
              KAFKA_PROCESS_ROLES                            = "broker,controller"
              KAFKA_LISTENERS                                = "PLAINTEXT://:9092,CONTROLLER://:9093"
              KAFKA_ADVERTISED_LISTENERS                     = "PLAINTEXT://kafka:9092"
              KAFKA_CONTROLLER_LISTENER_NAMES                = "CONTROLLER"
              KAFKA_CONTROLLER_QUORUM_VOTERS                 = "1@localhost:9093"
              KAFKA_LISTENER_SECURITY_PROTOCOL_MAP           = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
              KAFKA_INTER_BROKER_LISTENER_NAME               = "PLAINTEXT"
              KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR         = "1"
              KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR = "1"
              KAFKA_TRANSACTION_STATE_LOG_MIN_ISR            = "1"
              KAFKA_AUTO_CREATE_TOPICS_ENABLE                = "true"
            }
            content {
              name  = env.key
              value = env.value
            }
          }
        }
      }
    }
  }
}

resource "kubernetes_service_v1" "kafka" {
  metadata { name = "kafka" }
  spec {
    selector = { app = "kafka" }
    port {
      port        = 9092
      target_port = 9092
    }
  }
}
