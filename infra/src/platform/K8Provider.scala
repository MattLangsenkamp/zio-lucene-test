package platform

import besom.api.kubernetes as k8s
import besom.internal.Context
import utils.{EksOutput, Kubeconfig, Resource}
import besom.*

// Moved from explicit.providers.K8Provider — package renamed to platform.

final case class K8sInputs(eksCluster: Output[EksOutput])

object K8Provider extends Resource[K8sInputs, k8s.Provider, Unit, k8s.Provider]:

  override def make(inputParams: K8sInputs)(using Context): Output[k8s.Provider] =
    inputParams.eksCluster.flatMap { eks =>
      Kubeconfig
        .generateKubeconfigYaml(
          clusterName = eks.clusterName,
          clusterEndpoint = eks.clusterEndpoint,
          clusterCertificateAuthority = eks.clusterCertificateAuthority
        )
        .flatMap { config =>
          k8s.Provider(
            "eks-k8s-provider",
            k8s.ProviderArgs(kubeconfig = config)
          )
        }
    }

  // For local k3d: use the default kubeconfig written by 'make kubeconfig-local'
  override def makeLocal(inputParams: Unit)(using Context): Output[k8s.Provider] =
    k8s.Provider("k3d-k8s-provider", k8s.ProviderArgs())
