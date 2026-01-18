package utils

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s

case class EbsCsiDriverInput(
  eksCluster: Output[besom.api.aws.eks.Cluster],
  clusterOidcIssuer: Output[String],
  clusterOidcIssuerArn: Output[String],
  k8sProvider: Output[k8s.Provider]
)

case class EbsCsiDriverOutput(
  role: aws.iam.Role,
  addon: aws.eks.Addon
)

object EbsCsiDriver extends Resource[EbsCsiDriverInput, EbsCsiDriverOutput, Unit, Unit]:

  override def make(inputParams: EbsCsiDriverInput)(using Context): Output[EbsCsiDriverOutput] =
    for {
      role <- createEbsCsiRole(inputParams)
      addon <- installEbsCsiAddon(inputParams, role)
    } yield EbsCsiDriverOutput(
      role = role,
      addon = addon
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("EBS CSI driver not needed for local k3d")

  /**
   * IAM role for EBS CSI driver with IRSA (IAM Roles for Service Accounts)
   */
  private def createEbsCsiRole(params: EbsCsiDriverInput)(using Context): Output[aws.iam.Role] =
    val role = aws.iam.Role(
      "ebs-csi-driver-role",
      aws.iam.RoleArgs(
        assumeRolePolicy = for
          issuer <- params.clusterOidcIssuer
          arn <- params.clusterOidcIssuerArn
        yield s"""{
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Principal": {
                "Federated": "$arn"
              },
              "Action": "sts:AssumeRoleWithWebIdentity",
              "Condition": {
                "StringEquals": {
                  "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:kube-system:ebs-csi-controller-sa",
                  "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
                }
              }
            }
          ]
        }""",
        description = "IAM role for EBS CSI driver with IRSA"
      )
    )

    // Attach AWS managed policy for EBS CSI driver
    val policyAttachment = aws.iam.RolePolicyAttachment(
      "ebs-csi-driver-policy-attachment",
      aws.iam.RolePolicyAttachmentArgs(
        role = role.name,
        policyArn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
      ),
      opts(dependsOn = role)
    )

    policyAttachment.flatMap(_ => role)

  /**
   * Install EBS CSI driver as an EKS addon
   */
  private def installEbsCsiAddon(
    params: EbsCsiDriverInput,
    role: aws.iam.Role
  )(using Context): Output[aws.eks.Addon] =
    aws.eks.Addon(
      "ebs-csi-driver-addon",
      aws.eks.AddonArgs(
        clusterName = params.eksCluster.name,
        addonName = "aws-ebs-csi-driver",
        addonVersion = "v1.37.0-eksbuild.1", // Latest stable version
        serviceAccountRoleArn = role.arn,
        resolveConflictsOnCreate = "OVERWRITE",
        resolveConflictsOnUpdate = "OVERWRITE"
      ),
      opts(dependsOn = params.eksCluster)
    )
