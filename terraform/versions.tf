terraform {
  required_version = ">= 1.6"
  required_providers {
    # Creates/destroys the local kind cluster.
    kind = {
      source  = "tehcyx/kind"
      version = "~> 0.9"
    }
    # Installs Calico (the enforcing CNI) as a Helm release.
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.13"
    }
    # ghcr pull secret + the TLS keystore secret.
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.30"
    }
    # Generates the CA + server leaf cert (replaces the openssl calls in gen-tls.sh).
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
  }
}
