import org.virtuslab.yaml.*

// ── Data model ────────────────────────────────────────────────────────────────

case class AwsResource(
  logicalName: String,
  ssmPath: String,
  roles: Option[List[String]]
) derives YamlDecoder

case class AwsResources(
  sqs: Option[List[AwsResource]],
  s3: Option[List[AwsResource]],
  secrets: Option[List[AwsResource]]
) derives YamlDecoder

case class ManifestService(
  name: String,
  k8sPath: String,
  awsResources: Option[AwsResources]
) derives YamlDecoder

case class ServiceManifest(
  apiVersion: String,
  services: List[ManifestService]
) derives YamlDecoder

// ── Helpers ───────────────────────────────────────────────────────────────────

extension (s: ManifestService)
  def sqsResources: List[AwsResource]    = s.awsResources.flatMap(_.sqs).getOrElse(Nil)
  def s3Resources: List[AwsResource]     = s.awsResources.flatMap(_.s3).getOrElse(Nil)
  def secretResources: List[AwsResource] = s.awsResources.flatMap(_.secrets).getOrElse(Nil)

  def sqsReadQueues: List[AwsResource]  = sqsResources.filter(r => r.roles.getOrElse(Nil).contains("sqs:read"))
  def sqsWriteQueues: List[AwsResource] = sqsResources.filter(r => r.roles.getOrElse(Nil).contains("sqs:write"))
  def allowS3Write: Boolean             = s3Resources.exists(r => r.roles.getOrElse(Nil).contains("s3:write"))

extension (m: ServiceManifest)
  /** All unique SQS queues referenced across all services (deduplicated by logicalName). */
  def uniqueSqsQueues: List[AwsResource] =
    m.services.flatMap(_.sqsResources).distinctBy(_.logicalName)

  /** All unique S3 buckets referenced across all services (deduplicated by logicalName). */
  def uniqueS3Buckets: List[AwsResource] =
    m.services.flatMap(_.s3Resources).distinctBy(_.logicalName)

// ── Loader ────────────────────────────────────────────────────────────────────

object ServiceManifest:
  /** Load and parse service-manifest.yaml.
   *  Path is relative to the working directory when `pulumi up/preview` runs,
   *  which is the `infra/` subdirectory — so the repo-root manifest is at `../service-manifest.yaml`.
   */
  def load(path: String = "../service-manifest.yaml"): ServiceManifest =
    val source = scala.io.Source.fromFile(path)
    try
      source.mkString.as[ServiceManifest] match
        case Right(manifest) => manifest
        case Left(error)     => throw new RuntimeException(s"Failed to parse $path: $error")
    finally source.close()
