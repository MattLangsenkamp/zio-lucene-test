package util

import besom.*
import besom.api.aws
import besom.internal.Context
import utils.Resource

case class Route53Input(
    albHostname: String,
    zoneId: String,
    baseDomain: String,
    stackName: String
)

case class Route53Output(
    record: aws.route53.Record
)

object Route53 extends Resource[Route53Input, Route53Output, Unit, Unit] {

  override def make(inputParams: Route53Input)(using
      Context
  ): Output[Route53Output] = createDnsRecord(inputParams).map(Route53Output(_))

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("simply use port forwarding for local env")

  private def createDnsRecord(
      params: Route53Input
  )(using Context): Output[aws.route53.Record] = {

    val domain =
      if (params.stackName == "prod") params.baseDomain
      else s"${params.stackName}.${params.baseDomain}"

    // Create Route53 A record (alias) pointing to the ALB
    aws.route53.Record(
      s"alb-dns-record-${params.stackName}",
      aws.route53.RecordArgs(
        zoneId = params.zoneId,
        name = domain,
        `type` = "A",
        aliases = List(
          aws.route53.inputs.RecordAliasArgs(
            name = params.albHostname,
            zoneId = "Z35SXDOTRQ7X7K", // us-east-1 ALB hosted zone ID
            evaluateTargetHealth = true
          )
        )
      )
    )
  }
}
