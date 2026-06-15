# The Spring edge. Service on 8080 + readiness probe.
resource "kubernetes_deployment_v1" "api" {
  metadata {
    name   = "elevator-api"
    labels = { app = "elevator-api" }
  }
  spec {
    replicas = 1
    selector { match_labels = { app = "elevator-api" } }
    template {
      metadata { labels = { app = "elevator-api" } }
      spec {
        container {
          name              = "elevator-api"
          image             = var.api_image
          image_pull_policy = "IfNotPresent"
          port { container_port = 8080 }

          env_from {
            config_map_ref { name = kubernetes_config_map_v1.api.metadata[0].name }
          }

          readiness_probe {
            http_get {
              path = "/api/elevator"
              port = 8080
            }
            initial_delay_seconds = 10
            period_seconds        = 5
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

resource "kubernetes_service_v1" "api" {
  metadata { name = "elevator-api" }
  spec {
    selector = { app = "elevator-api" }
    port {
      port        = 8080
      target_port = 8080
    }
  }
}
