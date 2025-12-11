# Mill Build System Skill

This skill provides comprehensive guidance for working with the Mill build tool for Scala 3 projects.

## Core Concepts

Mill is a modern build tool for Scala that emphasizes:
- Fast incremental compilation
- Simple configuration using YAML or Scala code
- Built-in caching and parallelization
- Clean module hierarchy

## Project Structure

This project uses **programmatic build configuration** (Scala-based) to enable Docker support:

```
build.mill                # Programmatic build configuration
<service>/
  domainPublic/src/       # Scala source files
  domainPrivate/src/
  api/src/
  services/src/
  server/src/
```

All modules are defined in `build.mill` using Scala code, which allows for:
- Docker image building via mill-contrib-docker
- Complex module configurations
- Type-safe build definitions

## Common Commands

### Building
```bash
./mill <module>.compile          # Compile a specific module
./mill __.compile                # Compile all modules recursively
./mill ingestion.server.compile  # Example: compile ingestion server
```

### Testing
```bash
./mill <module>.test             # Run tests for a module
./mill __.test                   # Run all tests
```

### Resolving Modules
```bash
./mill resolve _                 # Show top-level modules
./mill resolve __                # Show all modules recursively
./mill resolve ingestion.__      # Show all ingestion submodules
```

### Cleaning
```bash
./mill clean                     # Clean all build artifacts
./mill <module>.clean            # Clean specific module
```

### Running
```bash
./mill <module>.run              # Run a module's main class
```

### Docker
```bash
./mill <service>.server.docker.build     # Build Docker image
./mill <service>.server.docker.push      # Push Docker image to registry
./mill show <service>.server.docker.tags # Show Docker image tags
```

## Module Configuration (Programmatic)

All modules are defined in `build.mill` using Scala. The build file structure:

```scala
//| mvnDeps: ["com.lihaoyi::mill-contrib-docker:1.1.0-RC3"]

package build
import mill._, scalalib._
import contrib.docker.DockerModule

object <service> extends Module {
  object domainPublic extends ScalaModule {
    def scalaVersion = "3.3.7"
  }

  object domainPrivate extends ScalaModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic)
  }

  object api extends ScalaModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic)
    def mvnDeps = Seq(
      mvn"com.softwaremill.sttp.tapir::tapir-core:1.11.10"
    )
  }

  object services extends ScalaModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic, domainPrivate, api)
    def mvnDeps = Seq(
      mvn"dev.zio::zio:2.1.17"
    )
  }

  object server extends ScalaModule with DockerModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic, domainPrivate, api, services)
    def mvnDeps = Seq(
      mvn"dev.zio::zio:2.1.17",
      mvn"dev.zio::zio-http:3.0.1",
      mvn"com.softwaremill.sttp.tapir::tapir-zio:1.11.10",
      mvn"com.softwaremill.sttp.tapir::tapir-zio-http-server:1.11.10"
    )

    object docker extends DockerConfig {
      def tags = List("<service>-server:latest")
      def baseImage = "azul/zulu-openjdk-alpine:21-jre"
      def exposedPorts = Seq(8080)
    }
  }
}
```

## Adding Dependencies

1. Find the dependency on Maven Central
2. Add to `mvnDeps` in the module definition in `build.mill`
3. Use format: `mvn"groupId::artifactId:version"` (note the `::` for Scala libraries)
4. For Scala libraries, the `::` automatically adds the Scala version suffix

Example:
```scala
def mvnDeps = Seq(
  mvn"dev.zio::zio:2.1.17",                    // Scala library (automatic _3 suffix)
  mvn"org.apache.lucene:lucene-core:9.9.1"     // Java library (single :)
)
```

## Creating a New Service

To create a new service with the standard 5-layer architecture:

1. Create directory structure:
```bash
mkdir -p <service>/{domainPublic,domainPrivate,api,services,server}/src
```

2. Add service definition to `build.mill`:
```scala
object <service> extends Module {
  object domainPublic extends ScalaModule {
    def scalaVersion = "3.3.7"
  }

  object domainPrivate extends ScalaModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic)
  }

  object api extends ScalaModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic)
    def mvnDeps = Seq(
      mvn"com.softwaremill.sttp.tapir::tapir-core:1.11.10"
    )
  }

  object services extends ScalaModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic, domainPrivate, api)
    def mvnDeps = Seq(
      mvn"dev.zio::zio:2.1.17"
    )
  }

  object server extends ScalaModule with DockerModule {
    def scalaVersion = "3.3.7"
    def moduleDeps = Seq(domainPublic, domainPrivate, api, services)
    def mvnDeps = Seq(
      mvn"dev.zio::zio:2.1.17",
      mvn"dev.zio::zio-http:3.0.1",
      mvn"com.softwaremill.sttp.tapir::tapir-zio:1.11.10",
      mvn"com.softwaremill.sttp.tapir::tapir-zio-http-server:1.11.10"
    )

    object docker extends DockerConfig {
      def tags = List("<service>-server:latest")
      def baseImage = "azul/zulu-openjdk-alpine:21-jre"
      def exposedPorts = Seq(8080)
    }
  }
}
```

3. Verify modules are recognized:
```bash
./mill resolve <service>.__
```

4. Test compilation:
```bash
./mill <service>.server.compile
```

5. Build Docker image:
```bash
./mill <service>.server.docker.build
```

## Docker Configuration

Server modules can be configured with Docker support by mixing in `DockerModule`:

```scala
object server extends ScalaModule with DockerModule {
  def scalaVersion = "3.3.7"
  def moduleDeps = Seq(domainPublic, domainPrivate, api, services)
  def mvnDeps = Seq(
    mvn"dev.zio::zio:2.1.17",
    mvn"dev.zio::zio-http:3.0.1"
  )

  object docker extends DockerConfig {
    def tags = List("my-service:latest", "my-service:v1.0.0")
    def baseImage = "azul/zulu-openjdk-alpine:21-jre"
    def exposedPorts = Seq(8080)
    def envVars = Map("ENV" -> "production")
    def jvmOptions = Seq("-Xmx512M")
  }
}
```

Common Docker tasks:
- `./mill <service>.server.docker.build` - Build Docker image
- `./mill <service>.server.docker.push` - Push to registry
- `./mill show <service>.server.docker.dockerfile` - Show generated Dockerfile
- `./mill show <service>.server.docker.tags` - Show image tags

## Troubleshooting

### Modules not recognized
- Ensure modules are defined in `build.mill`
- Check that module names match directory structure
- Run `./mill clean && ./mill resolve __` to refresh

### Compilation errors
- Check Scala version matches across all modules (should be 3.3.7)
- Verify Maven dependencies use `::` for Scala libraries, `:` for Java libraries
- Ensure `moduleDeps` reference existing modules within the same service

### Module dependency errors
- Within a service, use relative names: `Seq(domainPublic, api)`
- For cross-service dependencies, use full paths: `Seq(otherService.domainPublic)`
- Circular dependencies are not allowed

### Docker build errors
- Ensure Docker daemon is running
- Check that base image name is correct
- Verify exposed ports don't conflict with running containers

## Best Practices

1. **Always test builds after changes**: Run compilation after modifying `build.mill`
2. **Keep modules focused**: Each module should have a single responsibility
3. **Leverage caching**: Mill's incremental compilation is fast - use it often
4. **Module naming**: Use camelCase for module names (domainPublic, not domain-public)
5. **Docker tags**: Use semantic versioning for production images

## Service Architecture

Each service follows this 5-layer pattern:

1. **domainPublic**: Public domain models and types
2. **domainPrivate**: Internal domain logic
3. **api**: Effect-agnostic Tapir endpoint definitions
4. **services**: ZIO service implementations
5. **server**: ZIO HTTP server that wires everything together

This keeps the API layer portable and testable while allowing ZIO-specific implementation in services/server.

## Additional Resources

- [Mill Documentation](https://mill-build.org/)
- [Mill Scala Module](https://mill-build.org/mill/scalalib/intro.html)
- [Mill YAML Configuration](https://mill-build.org/mill/scalalib/intro.html)
