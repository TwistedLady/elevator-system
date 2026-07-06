{{/* Common labels put on every rendered object. */}}
{{- define "elevator.labels" -}}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: elevator
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* imagePullSecrets block, tolerated if the secret is absent (public / :local images). */}}
{{- define "elevator.imagePullSecrets" -}}
{{- if .Values.imagePullSecret }}
imagePullSecrets:
  - name: {{ .Values.imagePullSecret }}
{{- end }}
{{- end -}}
