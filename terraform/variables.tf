variable "cluster_name" {
  description = "kind cluster name."
  type        = string
  default     = "elevator"
}

variable "pod_subnet" {
  description = "Pod CIDR. Must match Calico's IP pool below."
  type        = string
  default     = "192.168.0.0/16"
}

variable "calico_version" {
  description = "tigera-operator (Calico) Helm chart version."
  type        = string
  default     = "v3.28.2"
}

variable "tls_password" {
  description = "Password protecting the api's PKCS12 keystore."
  type        = string
  default     = "changeit"
  sensitive   = true
}

variable "console_ca_path" {
  description = "Where to write the CA cert the CLI console bundles to trust the api."
  type        = string
  default     = "../elevator-console-cli/certs/elevator-ca.crt"
}

variable "tls_build_dir" {
  description = "Scratch dir for the generated key/cert/keystore files."
  type        = string
  default     = "/tmp/elevator-tls"
}
