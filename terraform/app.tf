# The Pekko brain. No Service (Kafka-only worker). Config from the ConfigMap.
resource "kubernetes_deployment_v1" "app" {
  metadata {
    name   = "elevator-app"
    labels = { app = "elevator-app" }
  }
  spec {
    replicas = 1
    selector { match_labels = { app = "elevator-app" } }
    template {
      metadata { labels = { app = "elevator-app" } }
      spec {
        container {
          name              = "elevator-app"
          image             = var.app_image
          image_pull_policy = "IfNotPresent"

          env_from {
            config_map_ref { name = kubernetes_config_map_v1.app.metadata[0].name }
          }

          resources {
            requests = { cpu = "100m", memory = "256Mi" }
            limits   = { cpu = "1", memory = "512Mi" }
          }
        }
      }
    }
  }
}
