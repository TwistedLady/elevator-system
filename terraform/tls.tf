# TLS for elevator-api (replaces gen-tls.sh). A small CA signs a server leaf cert (rustls rejects a
# self-signed CA used directly as the server cert). The tls provider does the cert work natively;
# only the PKCS12 keystore step still needs openssl (no TF provider emits .p12).

resource "tls_private_key" "ca" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "ca" {
  private_key_pem       = tls_private_key.ca.private_key_pem
  is_ca_certificate     = true
  validity_period_hours = 87600
  subject {
    common_name = "elevator-ca"
  }
  allowed_uses = ["cert_signing", "crl_signing", "digital_signature"]
}

resource "tls_private_key" "server" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_cert_request" "server" {
  private_key_pem = tls_private_key.server.private_key_pem
  subject {
    common_name = "elevator-api"
  }
  dns_names    = ["localhost", "elevator-api", "elevator-api.default.svc.cluster.local"]
  ip_addresses = ["127.0.0.1"]
}

resource "tls_locally_signed_cert" "server" {
  cert_request_pem      = tls_cert_request.server.cert_request_pem
  ca_private_key_pem    = tls_private_key.ca.private_key_pem
  ca_cert_pem           = tls_self_signed_cert.ca.cert_pem
  validity_period_hours = 87600
  allowed_uses          = ["digital_signature", "key_encipherment", "server_auth"]
}

resource "local_file" "server_crt" {
  filename        = "${var.tls_build_dir}/server.crt"
  content         = tls_locally_signed_cert.server.cert_pem
  file_permission = "0600"
}

resource "local_sensitive_file" "server_key" {
  filename        = "${var.tls_build_dir}/server.key"
  content         = tls_private_key.server.private_key_pem
  file_permission = "0600"
}

resource "local_file" "ca_crt" {
  filename        = "${var.tls_build_dir}/ca.crt"
  content         = tls_self_signed_cert.ca.cert_pem
  file_permission = "0644"
}

resource "local_file" "console_ca" {
  filename        = var.console_ca_path
  content         = tls_self_signed_cert.ca.cert_pem
  file_permission = "0644"
}

resource "null_resource" "keystore" {
  triggers = {
    server_cert = tls_locally_signed_cert.server.cert_pem
    ca_cert     = tls_self_signed_cert.ca.cert_pem
    password    = var.tls_password
  }
  provisioner "local-exec" {
    command = <<-EOT
      openssl pkcs12 -export \
        -in ${var.tls_build_dir}/server.crt \
        -inkey ${var.tls_build_dir}/server.key \
        -certfile ${var.tls_build_dir}/ca.crt \
        -name elevator-api -passout pass:${var.tls_password} \
        -out ${var.tls_build_dir}/keystore.p12
    EOT
  }
  depends_on = [local_file.server_crt, local_sensitive_file.server_key, local_file.ca_crt]
}

data "local_file" "keystore" {
  filename   = "${var.tls_build_dir}/keystore.p12"
  depends_on = [null_resource.keystore]
}

resource "kubernetes_secret" "api_tls" {
  metadata {
    name = "elevator-api-tls"
  }
  binary_data = {
    "keystore.p12" = data.local_file.keystore.content_base64
  }
  data = {
    password = var.tls_password
  }
  depends_on = [helm_release.calico]
}
