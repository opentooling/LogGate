{{- define "loggate.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "loggate.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s" (include "loggate.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "loggate.labels" -}}
app.kubernetes.io/name: {{ include "loggate.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "loggate.selectorLabels" -}}
app.kubernetes.io/name: {{ include "loggate.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
toString matters: a numeric tag (a unix timestamp, a build number) arrives from
--set as an int64, and printf "%s" on an int64 yields "%!s(int64=...)", which
kubelet rejects as an invalid image reference.
*/}}
{{- define "loggate.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag | toString -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{- define "loggate.secretName" -}}
{{- printf "%s-secrets" (include "loggate.fullname" .) -}}
{{- end -}}

{{/*
Database password: an explicit value wins, otherwise reuse whatever the
existing Secret already holds, otherwise generate one. Reusing the existing
value is what stops `helm upgrade` from silently rotating the password out
from under a running PostgreSQL.
*/}}
{{- define "loggate.databasePassword" -}}
{{- if .Values.secrets.databasePassword -}}
{{- .Values.secrets.databasePassword -}}
{{- else -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (include "loggate.secretName" .) -}}
{{- $current := "" -}}
{{- if $existing -}}
{{- if $existing.data -}}
{{- $current = (get $existing.data "database-password") -}}
{{- end -}}
{{- end -}}
{{- if $current -}}
{{- $current | b64dec -}}
{{- else -}}
{{- randAlphaNum 32 -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "loggate.databaseUrl" -}}
{{- if .Values.postgres.enabled -}}
{{- printf "jdbc:postgresql://%s-postgres:5432/%s" (include "loggate.fullname" .) .Values.postgres.database -}}
{{- else -}}
{{- .Values.externalDatabase.url -}}
{{- end -}}
{{- end -}}

{{- define "loggate.podSecurityContext" -}}
{{- if not .Values.openShift }}
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
{{- end }}
runAsNonRoot: true
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "loggate.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end -}}
