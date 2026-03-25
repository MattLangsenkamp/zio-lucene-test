package platform

import besom.*
import besom.api.kubernetes as k8s
import besom.json.*

/**
 * Installs ArgoCD via Helm and creates an ApplicationSet that templates one ArgoCD Application
 * per service defined in service-manifest.yaml.
 *
 * Generator: list (entries loaded from service-manifest.yaml via scala-yaml)
 * syncPolicy: automated for local (self-heals on every pulumi up), manual for cloud
 *
 * Local: repoURL = file:///repo  (requires k3d cluster created with --volume <repo>:/repo@all)
 * Cloud: repoURL = gitRepoUrl config value
 */

case class ArgoCDInput(
  k8sProvider: Output[k8s.Provider],
  repoUrl: Output[String],              // file:///repo for local; git remote URL for cloud
  env: String,                          // stack name passed as helm value → selects values.{env}.yaml
  services: List[(String, String)],     // (name, k8sPath) — parsed from service-manifest.yaml
  autoSync: Boolean = false,            // true for local; false for cloud (manual promotion)
  gitRevision: String = "HEAD",
  namespace: String = "zio-lucene",
  cluster: Option[Output[besom.api.aws.eks.Cluster]] = None,
  nodeGroup: Option[Output[besom.api.aws.eks.NodeGroup]] = None,
  serviceAccounts: Seq[Output[k8s.core.v1.ServiceAccount]] = Seq.empty
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
        opts(provider = prov, dependsOn = deps, retainOnDelete = true)
      )
    }

  private def installHelmRelease(
    ns: k8s.core.v1.Namespace,
    params: ArgoCDInput
  )(using Context): Output[k8s.helm.v3.Release] =
    params.k8sProvider.flatMap { prov =>
      // In local mode, mount /repo from the k3d node and override GIT_CONFIG_GLOBAL so
      // git's file:// subprocess transport trusts /repo regardless of UID ownership.
      // GIT_CONFIG_COUNT env vars are NOT inherited by git's upload-pack subprocess —
      // only a file-based config (via GIT_CONFIG_GLOBAL) works reliably.
      val repoServerValues: Output[Map[String, JsValue]] =
        if (params.autoSync)
          k8s.core.v1.ConfigMap(
            "argocd-repo-gitconfig",
            k8s.core.v1.ConfigMapArgs(
              metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                name      = "argocd-repo-gitconfig",
                namespace = "argocd"
              ),
              data = Map(".gitconfig" -> "[safe]\n\tdirectory = *\n")
            ),
            opts(provider = prov, dependsOn = ns)
          ).map { _ =>
            Map("repoServer" -> JsObject(
              "volumes" -> JsArray(
                JsObject("name" -> JsString("repo"),
                  "hostPath" -> JsObject("path" -> JsString("/repo"))),
                JsObject("name" -> JsString("gitconfig"),
                  "configMap" -> JsObject("name" -> JsString("argocd-repo-gitconfig")))
              ),
              "volumeMounts" -> JsArray(
                JsObject("name" -> JsString("repo"),      "mountPath" -> JsString("/repo")),
                JsObject("name" -> JsString("gitconfig"), "mountPath" -> JsString("/app/git-config"),
                  "readOnly" -> JsBoolean(true))
              ),
              "env" -> JsArray(
                JsObject("name" -> JsString("GIT_CONFIG_GLOBAL"), "value" -> JsString("/app/git-config/.gitconfig"))
              )
            ))
          }
        else Output(Map.empty)

      repoServerValues.flatMap { extraValues =>
      k8s.helm.v3.Release(
        "argocd",
        k8s.helm.v3.ReleaseArgs(
          name = "argocd",
          chart = "argo-cd",
          version = "7.7.3",
          namespace = ns.metadata.name,
          timeout = 1800,
          skipAwait = params.autoSync, // local: don't block pulumi on ArgoCD pod rollout
          repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
            repo = "https://argoproj.github.io/argo-helm"
          ),
          values = Map[String, JsValue](
            "server" -> JsObject(
              "service" -> JsObject("type" -> JsString("LoadBalancer"))
            ),
            "configs" -> JsObject(
              "cm" -> JsObject(
                "application.resourceTrackingMethod" -> JsString("annotation")
              ),
              "params" -> JsObject(
                "server.insecure" -> JsBoolean(true)
              )
            )
          ) ++ extraValues
        ),
        opts(provider = prov, dependsOn = ns)
      )
      }
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
              "namespace" -> JsString("argocd"),
              "finalizers" -> JsArray()
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
              "syncPolicy" -> (
                if (params.autoSync)
                  JsObject("automated" -> JsObject(
                    "prune"    -> JsBoolean(false),
                    "selfHeal" -> JsBoolean(true)
                  ))
                else
                  JsObject() // manual sync for cloud
              )
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
            dependsOn = helmRelease +: params.serviceAccounts.toList
          )
        )
      }
    }
