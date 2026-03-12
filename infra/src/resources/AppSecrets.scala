package resources

import besom.*
import besom.api.aws.Provider as AwsProvider
import besom.api.kubernetes as k8s

// Placeholder module — application secrets (Datadog, etc.) are provisioned outside Pulumi.
// The ClusterSecretStore (pointing to SSM ParameterStore) is managed by platform.ExternalSecrets.
// Per-service ExternalSecrets live in each service's helm chart under templates/external-secret.yaml.

case class AppSecretsInput(
  env: String,
  namespace: Output[String],
  awsProvider: Output[AwsProvider],
  k8sProvider: Output[k8s.Provider],
  esoHelmRelease: Option[Output[k8s.helm.v3.Release]] = None
)

case class AppSecretsOutput()

object AppSecrets:

  def make(params: AppSecretsInput)(using Context): Output[AppSecretsOutput] =
    Output(AppSecretsOutput())

  def makeLocal(params: AppSecretsInput)(using Context): Output[AppSecretsOutput] =
    Output(AppSecretsOutput())
