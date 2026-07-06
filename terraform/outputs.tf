output "cluster_name" {
  description = "kind cluster name (kubectl context is kind-<name>)."
  value       = kind_cluster.elevator.name
}

output "kubeconfig_path" {
  description = "Path to the kubeconfig kind wrote."
  value       = kind_cluster.elevator.kubeconfig_path
}

output "next_step" {
  description = "What to run after apply."
  value       = "cluster + Calico + TLS ready. Now: skaffold run (build + deploy the elevator chart)."
}
