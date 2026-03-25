import besom.api.kubernetes.meta.v1.inputs.ObjectMetaArgs
import besom.api.kubernetes.core.v1.{Namespace, ServiceAccount, ServiceAccountArgs}
import besom.internal.{Context, Output}
import besom.*
import besom.api.kubernetes.Provider

object K8ServiceAccount {
  def make(
      name: String,
      irsaRoleArnOutput: Output[String],
      namespace: String,
      namespaceResource: Output[Namespace],
      provider: Output[Provider]
  )(using
      Context
  ): Output[ServiceAccount] = {
    val saName: NonEmptyString = NonEmptyString(name).getOrElse(
      throw new IllegalArgumentException("service account name cannot be empty")
    )
    val sa = for {
      irsaRoleArn <- irsaRoleArnOutput
    } yield ServiceAccount(
      name = saName,
      args = ServiceAccountArgs(
        metadata = Some(
          ObjectMetaArgs(
            name = name,
            namespace = namespace,
            annotations = Some(
              Map(
                "eks.amazonaws.com/role-arn" -> irsaRoleArn
              )
            )
          )
        )
      ),
      opts = CustomResourceOptions(
        dependsOn = List(namespaceResource),
        provider = provider
      )
    )
    sa.flatten
  }

  def makeLocal(
      name: String,
      namespace: String,
      namespaceResource: Output[Namespace],
      provider: Output[Provider]
  )(using Context): Output[ServiceAccount] = {
    val saName: NonEmptyString = NonEmptyString(name).getOrElse(
      throw new IllegalArgumentException("service account name cannot be empty")
    )
    provider.flatMap { prov =>
      ServiceAccount(
        name = saName,
        args = ServiceAccountArgs(
          metadata = Some(ObjectMetaArgs(name = name, namespace = namespace))
        ),
        opts(provider = prov, dependsOn = namespaceResource)
      )
    }
  }
}
