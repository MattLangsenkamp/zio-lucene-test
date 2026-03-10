package platform

import besom.*
import besom.api.kubernetes as k8s
import besom.json.*

// Installs ArgoCD via helm and creates an ApplicationSet that templates one
// ArgoCD Application per service in service-manifest.yaml.
//
// Generator: list (hardcoded from service-manifest.yaml — avoids a runtime YAML parser dep)
// syncPolicy: {} (manual sync only — no automated GitOps promotion in Phase 1)
//
// Local: repoURL = file:///repo  (requires k3d cluster created with --volume <repo>:/repo@all)
// Cloud: repoURL = config value gitRepoUrl

case class ArgoCDInput(
  k8sProvider: Output[k8s.Provider],
  repoUrl: Output[String],              // file:///repo for local; git remote URL for cloud
  env: String,                          // stack name passed as helm value → selects values.{env}.yaml
  services: List[(String, String)],     // (name, k8sPath) — parsed from service-manifest.yaml
  gitRevision: String = "HEAD",
  namespace: String = "zio-lucene",
  cluster: Option[Output[besom.api.aws.eks.Cluster]] = None,
  nodeGroup: Option[Output[besom.api.aws.eks.NodeGroup]] = None
)

case class ArgoCDOutput(
  namespace: k8s.core.v1.Namespace,
  helmRelease: k8s.helm.v3.Release,
  applicationSet: k8s.apiextensions.CustomResource[JsValue]
)

object ArgoCD:

  def make(params: ArgoCDInput)(using Context): Output[ArgoCDOutput] =
    install(params)

  def makeLocal(params: ArgoCDInput)(using Context): Output[ArgoCDOutput] =
    install(params)

  private def install(params: ArgoCDInput)(using Context): Output[ArgoCDOutput] =
    for {
      ns   <- createNamespace(params)
      helm <- installHelmRelease(ns, params)
      appSet <- createApplicationSet(params, helm)
    } yield ArgoCDOutput(namespace = ns, helmRelease = helm, applicationSet = appSet)

  private def createNamespace(params: ArgoCDInput)(using Context): Output[k8s.core.v1.Namespace] =
    params.k8sProvider.flatMap { prov =>
      val deps = List(params.cluster, params.nodeGroup).flatten
      k8s.core.v1.Namespace(
        "argocd-namespace",
        k8s.core.v1.NamespaceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(name = "argocd")
        ),
        opts(provider = prov, dependsOn = deps)
      )
    }

  private def installHelmRelease(
    ns: k8s.core.v1.Namespace,
    params: ArgoCDInput
  )(using Context): Output[k8s.helm.v3.Release] =
    params.k8sProvider.flatMap { prov =>
      k8s.helm.v3.Release(
        "argocd",
        k8s.helm.v3.ReleaseArgs(
          name = "argocd",
          chart = "argo-cd",
          version = "7.7.3",
          namespace = ns.metadata.name,
          repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
            repo = "https://argoproj.github.io/argo-helm"
          ),
          values = Map[String, JsValue](
            "server" -> JsObject(
              "service" -> JsObject("type" -> JsString("LoadBalancer"))
            ),
            // Allow file:// repos for local dev
            "configs" -> JsObject(
              "cm" -> JsObject(
                "application.resourceTrackingMethod" -> JsString("annotation")
              ),
              "params" -> JsObject(
                "server.insecure" -> JsBoolean(true)
              )
            )
          )
        ),
        opts(provider = prov, dependsOn = ns)
      )
    }

  private def createApplicationSet(
    params: ArgoCDInput,
    helmRelease: k8s.helm.v3.Release
  )(using Context): Output[k8s.apiextensions.CustomResource[JsValue]] =
    params.k8sProvider.flatMap { prov =>
      // Build list generator elements — one entry per service from service-manifest.yaml
      val elements: JsValue = JsArray(
        params.services.map { case (name, k8sPath) =>
          JsObject(
            "name"    -> JsString(name),
            "k8sPath" -> JsString(k8sPath),
            "env"     -> JsString(params.env)
          )
        }*
      )

      params.repoUrl.flatMap { repoUrlStr =>
        val appSetSpec: JsValue = JsObject(
          "generators" -> JsArray(
            JsObject("list" -> JsObject("elements" -> elements))
          ),
          "template" -> JsObject(
            "metadata" -> JsObject(
              "name"      -> JsString("{{name}}"),
              "namespace" -> JsString("argocd")
            ),
            "spec" -> JsObject(
              "project" -> JsString("default"),
              "source" -> JsObject(
                "repoURL"        -> JsString(repoUrlStr),
                "targetRevision" -> JsString(params.gitRevision),
                "path"           -> JsString("{{k8sPath}}"),
                "helm" -> JsObject(
                  "valueFiles" -> JsArray(JsString("values/values.{{env}}.yaml")),
                  // Also pass env as an explicit helm value for templates that reference .Values.env
                  "values" -> JsString("env: {{env}}")
                )
              ),
              "destination" -> JsObject(
                "server"    -> JsString("https://kubernetes.default.svc"),
                "namespace" -> JsString(params.namespace)
              ),
              // Empty syncPolicy = manual sync only (no automated GitOps in Phase 1)
              "syncPolicy" -> JsObject()
            )
          )
        )

        k8s.apiextensions.CustomResource[JsValue](
          "services-appset",
          k8s.apiextensions.CustomResourceArgs[JsValue](
            apiVersion = "argoproj.io/v1alpha1",
            kind = "ApplicationSet",
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(namespace = "argocd"),
            spec = appSetSpec
          ),
          ComponentResourceOptions(
            providers = List(prov),
            dependsOn = List(helmRelease)
          )
        )
      }
    }
