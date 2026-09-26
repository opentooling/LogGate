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

{{/*
OIDC client secret, with the same reuse-or-generate rule as the database
password so an upgrade never rotates it out from under the realm.
*/}}
{{- define "loggate.oidcClientSecret" -}}
{{- if .Values.keycloak.enabled -}}
{{- .Values.keycloak.clientSecret -}}
{{- else if .Values.oidc.clientSecret -}}
{{- .Values.oidc.clientSecret -}}
{{- else -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (include "loggate.secretName" .) -}}
{{- $current := "" -}}
{{- if $existing -}}
{{- if $existing.data -}}
{{- $current = (get $existing.data "oidc-client-secret") -}}
{{- end -}}
{{- end -}}
{{- if $current -}}
{{- $current | b64dec -}}
{{- else -}}
{{- randAlphaNum 40 -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The issuer must be the URL the browser is redirected to, because that is what
Keycloak puts in the id_token's `iss`. The application therefore has to reach
that same URL, which locally means resolving it to the ingress.
*/}}
{{- define "loggate.issuerUri" -}}
{{- if .Values.keycloak.enabled -}}
{{- printf "%s/realms/%s" (.Values.keycloak.publicUrl | trimSuffix "/") .Values.keycloak.realm -}}
{{- else -}}
{{- required "oidc.issuerUri is required when keycloak.enabled is false" .Values.oidc.issuerUri -}}
{{- end -}}
{{- end -}}

{{/* Public callback URL, matching what is registered with the provider. */}}
{{- define "loggate.redirectUri" -}}
{{- if .Values.oidc.redirectUri -}}
{{- .Values.oidc.redirectUri -}}
{{- else if .Values.keycloak.enabled -}}
{{- printf "%s/login/oauth2/code/keycloak" (.Values.keycloak.appUrl | trimSuffix "/") -}}
{{- else -}}
{{- printf "https://%s/login/oauth2/code/keycloak" (include "loggate.publicHost" .) -}}
{{- end -}}
{{- end -}}

{{- define "loggate.databaseUrl" -}}
{{- if .Values.postgres.enabled -}}
{{- printf "jdbc:postgresql://%s-postgres:5432/%s" (include "loggate.fullname" .) .Values.postgres.database -}}
{{- else -}}
{{- .Values.externalDatabase.url -}}
{{- end -}}
{{- end -}}

{{/*
Whether to run under OpenShift's restricted-v2 SCC. The older top-level
`openShift` flag is honoured alongside `openshift.enabled`.
*/}}
{{- define "loggate.openshift" -}}
{{- if or .Values.openShift (and .Values.openshift .Values.openshift.enabled) -}}true{{- end -}}
{{- end -}}

{{/* The host the browser uses for the application: the Route's, or the Ingress's. */}}
{{- define "loggate.publicHost" -}}
{{- if .Values.route.enabled -}}
{{- required "route.host is required when route.enabled is true" .Values.route.host -}}
{{- else -}}
{{- .Values.ingress.host -}}
{{- end -}}
{{- end -}}

{{/* access.mode as the application spells it. */}}
{{- define "loggate.accessMode" -}}
{{- $mode := .Values.access.mode | default "teamLabel" -}}
{{- if eq $mode "teamLabel" -}}TEAM_LABEL
{{- else if eq $mode "open" -}}OPEN
{{- else -}}{{- fail (printf "access.mode must be teamLabel or open, not %q" $mode) -}}
{{- end -}}
{{- end -}}

{{- define "loggate.podSecurityContext" -}}
{{- if not (include "loggate.openshift" .) }}
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

{{- /*
A certificate authority to trust, from a Secret or a ConfigMap. A CA bundle is
public, so a ConfigMap is as good a home for it as a Secret, and is where
OpenShift's service CA and trusted-CA-bundle injection put one. Takes a dict
of `ca` (the caCertificate values) and `name` (what the setting is called, for
the error). Renders nothing when neither source is set.
*/}}
{{- define "loggate.caSource" -}}
{{- $ca := .ca | default dict -}}
{{- if and $ca.secretName $ca.configMapName -}}
{{- fail (printf "%s sets both secretName and configMapName; choose one." .name) -}}
{{- end -}}
{{- if $ca.secretName }}
secret:
  secretName: {{ $ca.secretName }}
  items:
    - key: {{ $ca.key | default "ca.crt" }}
      path: {{ $ca.key | default "ca.crt" }}
{{- else if $ca.configMapName }}
configMap:
  name: {{ $ca.configMapName }}
  items:
    - key: {{ $ca.key | default "ca.crt" }}
      path: {{ $ca.key | default "ca.crt" }}
{{- end -}}
{{- end -}}

{{- /*
The settings that make a client trust a mounted CA: an SSL bundle named after
the client, whose trust store is the file, and the client's setting naming that
bundle. Takes a dict of `setting` (the env var naming the bundle), `bundle` (its
name, which is also the mount directory's prefix) and `ca` (the caCertificate
values).
*/}}
{{- define "loggate.sslBundleEnv" -}}
- name: {{ .setting }}
  value: {{ .bundle }}
- name: SPRING_SSL_BUNDLE_PEM_{{ upper .bundle }}_TRUSTSTORE_CERTIFICATE
  value: file:/etc/loggate/{{ .bundle }}-ca/{{ .ca.key | default "ca.crt" }}
{{- end -}}
