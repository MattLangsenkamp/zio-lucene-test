package utils

import besom.*
import besom.api.aws.secretsmanager
import besom.api.aws.Provider as AwsProvider
import besom.internal.Context

final case class SecretStoreInput(
  dataDogApiKey: Output[String],
  awsProvider: Output[AwsProvider]
)

final case class SecretStoreOutput(
  secret: secretsmanager.Secret,
  secretVersion: secretsmanager.SecretVersion
)

object SecretStore extends Resource[SecretStoreInput, SecretStoreOutput, SecretStoreInput, SecretStoreOutput]:

  override def make(inputParams: SecretStoreInput)(using c: Context): Output[SecretStoreOutput] =
    for {
      secret <- createSecret(inputParams)
      secretVersion <- createSecretVersion(secret, inputParams)
    } yield SecretStoreOutput(
      secret = secret,
      secretVersion = secretVersion
    )

  override def makeLocal(inputParams: SecretStoreInput)(using c: Context): Output[SecretStoreOutput] =
    make(inputParams)

  private def createSecret(params: SecretStoreInput)(using Context): Output[secretsmanager.Secret] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    secretsmanager.Secret(
      "datadog-api-key-secret",
      secretsmanager.SecretArgs(
        name = "zio-lucene/datadog-api-key",
        description = "Datadog API key for monitoring",
        recoveryWindowInDays = 0  // Immediate deletion when destroyed
      ),
      opts(provider = providerResource)
    )

  private def createSecretVersion(
    secret: secretsmanager.Secret,
    params: SecretStoreInput
  )(using Context): Output[secretsmanager.SecretVersion] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    params.dataDogApiKey.flatMap { apiKey =>
      secretsmanager.SecretVersion(
        "datadog-api-key-secret-version",
        secretsmanager.SecretVersionArgs(
          secretId = secret.id,
          secretString = apiKey
        ),
        opts(provider = providerResource)
      )
    }
