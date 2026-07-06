# Dummy GHCR pull secret. Images on kind are local (:local) or public, so this just satisfies the
# imagePullSecrets reference in the chart (kind-calico-up.sh step 5). CD replaces it with a real
# token. A missing secret is tolerated, but creating it keeps pods from warning.
resource "kubernetes_secret" "ghcr_pull" {
  metadata {
    name = "ghcr-pull"
  }
  type = "kubernetes.io/dockerconfigjson"
  data = {
    ".dockerconfigjson" = jsonencode({
      auths = {
        "ghcr.io" = {
          username = "none"
          password = "none"
          auth     = base64encode("none:none")
        }
      }
    })
  }
  depends_on = [helm_release.calico]
}
