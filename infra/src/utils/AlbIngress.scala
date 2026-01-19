package utils

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s

case class AlbIngressInput(
  eksCluster: besom.api.aws.eks.Cluster,
  namespace: Output[String],
  readerServiceName: Output[String],
  stackName: String,
  hostedZoneIdConfig: Output[Option[String]],
  baseDomainConfig: Output[Option[String]],
  certificateArnConfig: Output[Option[String]],
  k8sProvider: Output[k8s.Provider],
  albController: AlbControllerOutput
)

case class AlbIngressOutput(
  ingress: k8s.networking.v1.Ingress,
  dnsRecord: Option[aws.route53.Record]
)

object AlbIngress extends Resource[AlbIngressInput, AlbIngressOutput, Unit, Unit]:

  override def make(inputParams: AlbIngressInput)(using Context): Output[AlbIngressOutput] =
    for {
      ingress <- createIngress(inputParams)
      dnsRecord <- createDnsRecord(inputParams, ingress)
    } yield AlbIngressOutput(
      ingress = ingress,
      dnsRecord = dnsRecord
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("no alb ingress needed locally due to usage of k3d")

  private def createIngress(
    params: AlbIngressInput
  )(using Context): Output[k8s.networking.v1.Ingress] =
    params.certificateArnConfig.flatMap { certArnOpt =>
      // Build annotations based on whether certificate is configured
      val baseAnnotations = Map(
        "kubernetes.io/ingress.class" -> "alb",
        "alb.ingress.kubernetes.io/scheme" -> "internet-facing",
        "alb.ingress.kubernetes.io/target-type" -> "ip",
        "alb.ingress.kubernetes.io/healthcheck-path" -> "/health",
        "alb.ingress.kubernetes.io/healthcheck-interval-seconds" -> "15",
        "alb.ingress.kubernetes.io/healthcheck-timeout-seconds" -> "5",
        "alb.ingress.kubernetes.io/healthy-threshold-count" -> "2",
        "alb.ingress.kubernetes.io/unhealthy-threshold-count" -> "2"
      )

      val annotations = certArnOpt match
        case Some(certArn) =>
          // HTTPS with SSL certificate
          baseAnnotations ++ Map(
            "alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}, {"HTTPS": 443}]""",
            "alb.ingress.kubernetes.io/certificate-arn" -> certArn,
            "alb.ingress.kubernetes.io/ssl-redirect" -> "443"
          )
        case None =>
          // HTTP only (for testing/local)
          baseAnnotations + ("alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}]""")

      params.k8sProvider.flatMap { prov =>
        k8s.networking.v1.Ingress(
          "zio-lucene-ingress",
          k8s.networking.v1.IngressArgs(
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "zio-lucene-ingress",
              namespace = params.namespace,
              annotations = annotations
            ),
            spec = k8s.networking.v1.inputs.IngressSpecArgs(
              rules = List(
                k8s.networking.v1.inputs.IngressRuleArgs(
                  http = k8s.networking.v1.inputs.HttpIngressRuleValueArgs(
                    paths = List(
                      k8s.networking.v1.inputs.HttpIngressPathArgs(
                        path = "/search",
                        pathType = "Prefix",
                        backend = k8s.networking.v1.inputs.IngressBackendArgs(
                          service = k8s.networking.v1.inputs.IngressServiceBackendArgs(
                            name = params.readerServiceName,
                            port = k8s.networking.v1.inputs.ServiceBackendPortArgs(
                              number = 80
                            )
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          ),
          opts(dependsOn = List(params.eksCluster, params.albController.helmRelease), provider = prov)
        )
      }
    }

  private def createDnsRecord(
    params: AlbIngressInput,
    ingress: k8s.networking.v1.Ingress
  )(using Context): Output[Option[aws.route53.Record]] =
    params.hostedZoneIdConfig.flatMap { zoneIdOpt =>
      params.baseDomainConfig.flatMap { domainOpt =>
        (zoneIdOpt, domainOpt) match
          case (Some(zoneId), Some(baseDomain)) =>
            // Determine full domain name based on stack
            val domain = if (params.stackName == "prod") baseDomain else s"${params.stackName}.$baseDomain"

            // Extract ALB hostname from ingress status
            ingress.status.loadBalancer.ingress.flatMap { maybeIngressList =>
              maybeIngressList
                .flatMap(_.headOption)  // Get first element from Iterable
                .map(_.hostname)         // Get the Output[Option[String]] field
                .getOrElse(Output(None)) // If no ingress, return None
                .flatMap {
                  case Some(hostname) =>
                    // Create Route53 A record (alias) pointing to the ALB
                    aws.route53.Record(
                      "alb-dns-record",
                      aws.route53.RecordArgs(
                        zoneId = zoneId,
                        name = domain,
                        `type` = "A",
                        aliases = List(
                          aws.route53.inputs.RecordAliasArgs(
                            name = hostname,
                            zoneId = "Z35SXDOTRQ7X7K", // us-east-1 ALB hosted zone ID
                            evaluateTargetHealth = true
                          )
                        )
                      )
                    ).map(Some(_))
                  case None =>
                    // ALB hostname not available yet, skip DNS record for now
                    Output(None)
                }
            }
          case _ =>
            Output(None)
      }
    }
