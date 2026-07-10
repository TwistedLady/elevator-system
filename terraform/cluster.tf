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
      # kind maps the api NodePort (30080) to the host so the console reaches the api at
      # localhost:8080 with no port-forward (values-dev.yaml sets the api Service to NodePort 30080).
      extra_port_mappings {
        container_port = 30080
        host_port      = 8080
      }
    }
  }
}

# Both providers talk to the freshly-created cluster via its exported credentials — no ~/.kube/config
# dependency, so this survives kind rewriting the kubeconfig on every recreate.
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

# Calico via the tigera-operator Helm chart (replaces `kubectl apply -f calico.yaml`).
# calico-node runs as a hostNetwork DaemonSet, so it comes up on a cluster that has no CNI yet.
resource "helm_release" "calico" {
  name             = "calico"
  repository       = "https://docs.tigera.io/calico/charts"
  chart            = "tigera-operator"
  version          = var.calico_version
  namespace        = "tigera-operator"
  create_namespace = true
  # Wait until the operator has rolled out; NetworkPolicy is enforced once calico-node is Ready.
  wait    = true
  timeout = 300

  # Pin the IP pool to kind's pod subnet; VXLAN (no BGP) is the safe encapsulation on kind.
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
