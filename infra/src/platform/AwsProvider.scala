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

  // Local: fake credentials + skip validation so Pulumi targets LocalStack.
  // Endpoint routing (SQS, SSM, S3, etc.) is handled via AWS_ENDPOINT_URL=http://localhost:4566
  // set in the Makefile for all local `pulumi up/preview/destroy` invocations.
  override def makeLocal(inputParams: AwsProviderInputs)(using Context): Output[aws.Provider] =
    aws.Provider(
      "aws-provider",
      aws.ProviderArgs(
        region = "us-east-1",
        accessKey = "test",
        secretKey = "test",
        skipCredentialsValidation = true,
        skipMetadataApiCheck = true,
        skipRequestingAccountId = true,
        s3UsePathStyle = true,
        defaultTags = Some(
          aws.inputs.ProviderDefaultTagsArgs(
            Output(Some(Map("env_name" -> inputParams.envName)))
          )
        )
      )
    )
