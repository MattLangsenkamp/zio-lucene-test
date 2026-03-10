package platform

import besom.api.aws
import besom.internal.Context
import utils.Resource
import besom.*

// Moved from explicit.providers.AwsProvider — package renamed to platform.

final case class AwsProviderInputs(envName: String)

object AwsProvider extends Resource[AwsProviderInputs, aws.Provider, AwsProviderInputs, aws.Provider]:

  override def make(inputParams: AwsProviderInputs)(using Context): Output[aws.Provider] =
    aws.Provider(
      "aws-provider",
      aws.ProviderArgs(
        defaultTags = Some(
          aws.inputs.ProviderDefaultTagsArgs(
            Output(Some(Map("env_name" -> inputParams.envName)))
          )
        )
      )
    )

  override def makeLocal(inputParams: AwsProviderInputs)(using Context): Output[aws.Provider] =
    make(inputParams)
