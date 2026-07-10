# The kind cluster (replaces kind-calico-up.sh steps 1-2 + scripts/kind-calico.yaml).
# disableDefaultCNI => no kindnet; Calico is installed below so NetworkPolicy is ENFORCED.
resource "kind_cluster" "elevator" {
  name           = var.cluster_name
  wait_for_ready = true

  kind_config {
    kind        = "Cluster"
    api_version = "kind.x-k8s.io/v1alpha4"

    networking {
      disable_default_cni = true
      pod_subnet          = var.pod_subnet
    }

    node {
      role = "control-plane"
      extra_port_mappings {
        container_port = 30080
        host_port      = 8080
      }
    }
  }
}

provider "kubernetes" {
  host                   = kind_cluster.elevator.endpoint
  client_certificate     = kind_cluster.elevator.client_certificate
  client_key             = kind_cluster.elevator.client_key
  cluster_ca_certificate = kind_cluster.elevator.cluster_ca_certificate
}

provider "helm" {
  kubernetes {
    host                   = kind_cluster.elevator.endpoint
    client_certificate     = kind_cluster.elevator.client_certificate
    client_key             = kind_cluster.elevator.client_key
    cluster_ca_certificate = kind_cluster.elevator.cluster_ca_certificate
  }
}

resource "helm_release" "calico" {
  name             = "calico"
  repository       = "https://docs.tigera.io/calico/charts"
  chart            = "tigera-operator"
  version          = var.calico_version
  namespace        = "tigera-operator"
  create_namespace = true
  wait    = true
  timeout = 300

  values = [yamlencode({
    installation = {
      calicoNetwork = {
        ipPools = [{
          cidr          = var.pod_subnet
          encapsulation = "VXLANCrossSubnet"
        }]
      }
    }
  })]

  depends_on = [kind_cluster.elevator]
}
