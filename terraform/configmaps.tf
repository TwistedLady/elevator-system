# Same config as k8s/configmap.yaml, but as Terraform resources.
resource "kubernetes_config_map_v1" "app" {
  metadata { name = "elevator-app-config" }
  data = {
    ELEVATOR_KAFKA_BOOTSTRAP_SERVERS = "kafka:9092"
    ELEVATOR_KAFKA_GROUP_ID          = "elevator-app"
    ELEVATOR_KAFKA_COMMAND_TOPIC     = "elevator-commands"
    ELEVATOR_KAFKA_STATE_TOPIC       = "elevator-state"
  }
}

resource "kubernetes_config_map_v1" "api" {
  metadata { name = "elevator-api-config" }
  data = {
    SERVER_PORT                    = "8080"
    SPRING_KAFKA_BOOTSTRAP_SERVERS = "kafka:9092"
    ELEVATOR_COMMAND_TOPIC         = "elevator-commands"
    ELEVATOR_STATE_TOPIC           = "elevator-state"
  }
}
