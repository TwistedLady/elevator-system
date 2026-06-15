# Talk to the local kind cluster (no cloud). kind writes this context to ~/.kube/config.
provider "kubernetes" {
  config_path    = "~/.kube/config"
  config_context = "kind-elevator"
}
