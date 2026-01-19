package utils

import besom.*
import besom.api.aws

case class OidcProviderInput(
  clusterOidcIssuer: Output[String],
  cluster: besom.api.aws.eks.Cluster
)

case class OidcProviderOutput(
  provider: aws.iam.OpenIdConnectProvider,
  providerArn: Output[String],
  issuerUrl: Output[String]
)

object OidcProvider extends Resource[OidcProviderInput, OidcProviderOutput, Unit, Unit]:

  override def make(inputParams: OidcProviderInput)(using Context): Output[OidcProviderOutput] =
    for {
      provider <- aws.iam.OpenIdConnectProvider(
        "eks-oidc-provider",
        aws.iam.OpenIdConnectProviderArgs(
          url = inputParams.clusterOidcIssuer,
          clientIdLists = Output(List("sts.amazonaws.com")),
          thumbprintLists = Output(List("9e99a48a9960b14926bb7f3b02e22da2b0ab7280"))
        ),
        opts(dependsOn = inputParams.cluster)
      )
    } yield OidcProviderOutput(
      provider = provider,
      providerArn = provider.arn,
      issuerUrl = inputParams.clusterOidcIssuer
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("no OIDC provider needed locally due to usage of k3d")
