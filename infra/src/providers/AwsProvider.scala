package explicit.providers

import besom.api.aws
import besom.internal.Context
import utils.Resource

import besom.*

final case class AwsProviderInputs(envName: String)

object AwsProvider
    extends Resource[
      AwsProviderInputs,
      aws.Provider,
      AwsProviderInputs,
      aws.Provider
    ] {

  override def make(
      inputParams: AwsProviderInputs
  )(using Context): Output[aws.Provider] = {

    val defaultTagArgs: besom.internal.Input.Optional[
      besom.api.aws.inputs.ProviderDefaultTagsArgs
    ] =
      Some(
        aws.inputs.ProviderDefaultTagsArgs.apply(
          Output(Some(
            Map(
              "yes" -> "no",
              "envName" -> inputParams.envName
            )
          ))
        )
      )

    val providerArgs = aws.ProviderArgs(defaultTags = defaultTagArgs)

    aws.Provider(
      "aws-provider",
      providerArgs
    )
  }

  override def makeLocal(inputParams: AwsProviderInputs)(using
      Context
  ): Output[aws.Provider] = make(inputParams)
}
