package utils

import besom.*

object Kubeconfig:
  /**
   * Generates a kubeconfig YAML string from EKS cluster outputs.
   * This kubeconfig uses the AWS CLI for authentication (aws eks get-token).
   *
   * @param clusterName EKS cluster name
   * @param clusterEndpoint API server endpoint URL
   * @param clusterCertificateAuthority Base64-encoded CA certificate
   * @param region AWS region (default: us-east-1)
   * @return Output[String] containing kubeconfig YAML
   */
  def generateKubeconfigYaml(
    clusterName: Output[String],
    clusterEndpoint: Output[String],
    clusterCertificateAuthority: Output[String],
    region: String = "us-east-1"
  )(using Context): Output[String] =
    for {
      name <- clusterName
      endpoint <- clusterEndpoint
      caCert <- clusterCertificateAuthority
    } yield s"""apiVersion: v1
kind: Config
clusters:
- cluster:
    server: $endpoint
    certificate-authority-data: $caCert
  name: $name
contexts:
- context:
    cluster: $name
    user: $name
  name: $name
current-context: $name
users:
- name: $name
  user:
    exec:
      apiVersion: client.authentication.k8s.io/v1beta1
      command: aws
      args:
        - eks
        - get-token
        - --cluster-name
        - $name
        - --region
        - $region
      interactiveMode: IfAvailable
"""
