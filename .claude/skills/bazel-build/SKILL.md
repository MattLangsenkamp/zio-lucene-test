---
name: bazel-build
description: Bazel build system configuration and management for Scala 3 projects using MODULE.bazel (modern approach). Use when working on: (1) Bazel workspace setup and initialization, (2) MODULE.bazel configuration for Scala 3 projects, (3) Maven dependency management in Bazel, (4) Scala rules configuration and compiler options, (5) Build target definitions (scala_binary, scala_library), (6) Build optimization and caching strategies, (7) Remote execution setup, (8) Troubleshooting build issues, (9) Resolving Scala 3 macro dependency problems, or (10) Bootstrapping new services with layered architecture (domain-public, domain-private, api, services, server).
---

# Bazel Build Agent

Expert guidance for Bazel build system configuration, focusing on modern MODULE.bazel approach for Scala 3 projects with ZIO.

## Core Responsibilities

Provide expert assistance with:
- Bazel workspace initialization and configuration
- MODULE.bazel dependency management (modern approach, not legacy WORKSPACE)
- Scala 3 rules configuration and build targets
- Maven dependency resolution
- Build optimization and troubleshooting
- Project-specific build patterns and custom macros

## Key Knowledge Areas

### 1. Modern Bazel with MODULE.bazel

Use MODULE.bazel for dependency management instead of legacy WORKSPACE files. MODULE.bazel provides:
- Better dependency resolution
- Automatic transitive dependency management
- Version conflict resolution
- Cleaner syntax

See `references/bazel-module-setup.md` for detailed MODULE.bazel patterns and setup.

### 2. Scala 3 + ZIO Build Configuration

This project uses Scala 3 with ZIO, which requires special attention to:

#### TASTy Version Compatibility
**Critical Issue:** Scala 3 TASTy format is not backwards compatible between minor versions.

**Solution:**
- Use Scala 3.3.7 with ZIO 2.1.17+
- Always verify library versions on Maven Central match your Scala version
- Check the Scala version used to compile dependencies before adding them

**Verification Command:**
```bash
bazel query @maven//... | grep <package>
```

#### Scala 3 Macro Dependencies
**Critical Issue:** ZIO macros fail with `NoClassDefFoundError` for izumi-reflect dependencies due to compile-time classpath issues.

**Root Cause:** Scala 3 macros executed at compile-time need all transitive dependencies available on the classpath, but Bazel doesn't automatically include macro-time dependencies.

**Solution:** Explicitly include all compile-time dependencies in your build targets:
```python
deps = [
    "@maven//:dev_zio_zio_3",
    "@maven//:dev_zio_izumi_reflect_3",
    "@maven//:dev_zio_izumi_reflect_thirdparty_boopickle_shaded_3",
    "@maven//:dev_zio_zio_stacktracer_3",
    "@maven//:dev_zio_zio_internal_macros_3",
]
```

**Why This Happens:**
- ZIO uses macros for type-level programming and effect construction
- These macros depend on izumi-reflect for runtime type information
- Without explicit deps, the macro expansion fails during compilation
- Maven/sbt handle this automatically, but Bazel requires explicit declaration

**Prevention:**
- Always check ZIO library dependencies on Maven Central
- Include all macro-related deps (izumi-reflect, stacktracer, internal-macros)
- Use custom macros (see Project-Specific Patterns) to avoid repetition

See `references/scala-rules.md` for comprehensive Scala 3 configuration.

### 3. Maven Dependency Management

#### Querying Available Dependencies
```bash
# Find all available Maven dependencies
bazel query @maven//... | grep <package>

# Show transitive dependencies of a target
bazel query "deps(@maven//:target)"

# Understand dependency tree
bazel query "deps(@maven//:dev_zio_zio_3)" --output=graph
```

#### Adding New Dependencies
1. Add to MODULE.bazel in the `maven.install()` section
2. Run `bazel mod deps` to update dependency graph
3. Verify with `bazel query @maven//... | grep <new-package>`
4. Add to build targets as needed

#### Common Maven Dependency Patterns
```python
# Standard dependency
"@maven//:org_apache_lucene_lucene_core"

# Scala 3 dependency (note the _3 suffix)
"@maven//:dev_zio_zio_3"

# Shaded dependency
"@maven//:dev_zio_izumi_reflect_thirdparty_boopickle_shaded_3"
```

See `references/maven-deps.md` for detailed Maven dependency management patterns.

### 4. Project-Specific Patterns

This project uses custom Bazel macros defined in `defs.bzl` to reduce boilerplate and enforce consistency:

#### Custom Macros

**`zio_scala_library()`** - For library targets
- Automatically includes standard ZIO dependencies
- Adds all required macro-time dependencies
- Enforces consistent Scala compiler options

**`zio_scala_binary()`** - For executable targets
- Includes ZIO runtime dependencies
- Configures main class properly
- Sets up JVM options for ZIO applications

**Pre-configured Dependency Sets:**
- `ZIO_DEPS` - Complete ZIO dependency set (including macro deps)
- `LUCENE_DEPS` - Lucene search dependencies

**Usage Example:**
```python
load("//:defs.bzl", "zio_scala_library", "ZIO_DEPS", "LUCENE_DEPS")

zio_scala_library(
    name = "my_library",
    srcs = glob(["*.scala"]),
    deps = ZIO_DEPS + LUCENE_DEPS + [
        "//other:dependency",
    ],
)
```

**Benefits:**
- No need to manually list all ZIO macro dependencies
- Consistent build configuration across targets
- Easy to update dependencies project-wide

### 5. Build Optimization

#### Caching Strategies
- Use `--disk_cache` for local build caching
- Configure remote cache for team builds
- Leverage `--repository_cache` for external dependencies

#### Build Performance
- Use `--jobs` to control parallelism
- Enable `--remote_download_minimal` for faster builds
- Use `--keep_going` to continue on errors

#### Debugging Builds
```bash
# Verbose output
bazel build //target --verbose_failures

# Show full command lines
bazel build //target --subcommands

# Analyze why a target rebuilt
bazel build //target --explain=explain.txt

# Check actual classpath used
cat bazel-out/k8-fastbuild/bin/path/to/target.params
```

See `references/bazel-best-practices.md` for comprehensive build optimization techniques.

### 6. Common Issues and Solutions

#### Issue: "No such package" error
**Cause:** MODULE.bazel not properly configured or dependency not declared

**Solution:**
1. Check MODULE.bazel has the dependency in `maven.install()`
2. Run `bazel mod deps` to refresh
3. Verify with `bazel query @maven//...`

#### Issue: "NoClassDefFoundError" at compile time
**Cause:** Missing compile-time dependencies (especially for macros)

**Solution:**
1. Identify missing class from error message
2. Query Maven for the dependency: `bazel query @maven//... | grep <class-package>`
3. Add to deps list explicitly
4. For ZIO, use `ZIO_DEPS` macro to get all required dependencies

#### Issue: TASTy version mismatch
**Cause:** Library compiled with different Scala 3 minor version

**Solution:**
1. Check library's Scala version on Maven Central
2. Update Scala version in MODULE.bazel to match
3. Ensure all dependencies use compatible Scala version
4. Rebuild with `bazel clean --expunge` if necessary

#### Issue: Build fails with cryptic macro errors
**Cause:** Macro dependencies not available at compile time

**Solution:**
1. Use `zio_scala_library()` macro instead of raw `scala_library()`
2. Add `ZIO_DEPS` to deps list
3. Check `defs.bzl` for required macro dependencies
4. Verify all izumi-reflect dependencies are included

### 7. Analysis and Validation

#### Workspace Validation
Use the provided validation script:
```bash
.claude/skills/bazel-build/scripts/validate-build.sh
```

This checks:
- MODULE.bazel syntax
- Required dependencies present
- Scala rules properly configured
- Common configuration issues

#### Dependency Analysis
```bash
# Show all external dependencies
bazel query @maven//... --output=label

# Find reverse dependencies (what depends on X)
bazel query "rdeps(//..., //some:target)"

# Show dependency path
bazel query "somepath(//start:target, //end:target)"

# Analyze build graph
bazel query //... --output=graph > graph.dot
dot -Tpng graph.dot > graph.png
```

#### Build Health Check
```bash
# Test all targets build
bazel build //...

# Test with clean build
bazel clean && bazel build //...

# Verify no cache issues
bazel clean --expunge && bazel build //...
```

## Workflow Guide

### Setting Up a New Bazel Workspace

1. **Initialize Workspace**
   ```bash
   .claude/skills/bazel-build/scripts/init-workspace.sh
   ```

2. **Configure MODULE.bazel**
   - Add Scala rules
   - Add Maven dependencies
   - Set Scala version (3.3.7 recommended)

3. **Create Custom Macros** (optional)
   - Define in `defs.bzl`
   - Include common dependency sets
   - Enforce project conventions

4. **Validate Configuration**
   ```bash
   .claude/skills/bazel-build/scripts/validate-build.sh
   ```

### Adding a New Scala Library

1. Create BUILD.bazel in library directory
2. Use `zio_scala_library()` macro for ZIO projects
3. Include `ZIO_DEPS` if using ZIO
4. Add any additional dependencies
5. Test build: `bazel build //path/to:library`

### Bootstrapping a New Service

This project follows a layered architecture pattern for services. Each service has 5 layers:

1. **domain-public/** - Public domain models and types (visible to all services)
2. **domain-private/** - Internal domain models (visible only within service)
3. **api/** - Public API interfaces and contracts
4. **services/** - Business logic implementations
5. **server/** - Server/runtime configuration and main entry point

**Directory Structure:**
```
app/
├── <service-name>/
│   ├── domain-public/
│   │   ├── BUILD.bazel
│   │   └── *.scala
│   ├── domain-private/
│   │   ├── BUILD.bazel
│   │   └── *.scala
│   ├── api/
│   │   ├── BUILD.bazel
│   │   └── *.scala
│   ├── services/
│   │   ├── BUILD.bazel
│   │   └── *.scala
│   └── server/
│       ├── BUILD.bazel
│       └── *.scala
```

**Steps to Bootstrap a New Service:**

1. **Create directory structure:**
   ```bash
   mkdir -p app/<service-name>/{domain-public,domain-private,api,services,server}
   ```

2. **Create BUILD.bazel in domain-public:**
   ```python
   load("//:defs.bzl", "zio_scala_library")

   zio_scala_library(
       name = "domain-public",
       visibility = ["//visibility:public"],
   )
   ```

3. **Create BUILD.bazel in domain-private:**
   ```python
   load("//:defs.bzl", "zio_scala_library")

   zio_scala_library(
       name = "domain-private",
       visibility = ["//app/<service-name>:__subpackages__"],
   )
   ```

4. **Create BUILD.bazel in api:**
   ```python
   load("//:defs.bzl", "zio_scala_library")

   zio_scala_library(
       name = "api",
       visibility = ["//visibility:public"],
       deps = [
           "//app/<service-name>/domain-public",
       ],
   )
   ```

5. **Create BUILD.bazel in services:**
   ```python
   load("//:defs.bzl", "zio_scala_library")

   zio_scala_library(
       name = "services",
       visibility = ["//app/<service-name>:__subpackages__"],
       deps = [
           "//app/<service-name>/domain-public",
           "//app/<service-name>/domain-private",
           "//app/<service-name>/api",
       ],
   )
   ```

6. **Create BUILD.bazel in server:**
   ```python
   load("//:defs.bzl", "zio_scala_library")

   zio_scala_library(
       name = "server",
       visibility = ["//app/<service-name>:__subpackages__"],
       deps = [
           "//app/<service-name>/domain-public",
           "//app/<service-name>/domain-private",
           "//app/<service-name>/api",
           "//app/<service-name>/services",
       ],
   )
   ```

**Key Architecture Patterns:**

- **Visibility Controls:**
  - `domain-public` and `api` are public (can be used by other services)
  - `domain-private`, `services`, and `server` are package-private (only within service)

- **Dependency Flow:**
  - `domain-public` → no dependencies (foundation)
  - `domain-private` → no dependencies (internal foundation)
  - `api` → depends on `domain-public`
  - `services` → depends on `domain-public`, `domain-private`, `api`
  - `server` → depends on all layers

- **Source Files:**
  - The `zio_scala_library()` macro automatically globs all `*.scala` files in the directory
  - No need to specify `srcs` parameter
  - Just add `.scala` files to the directory and they'll be included

**Example: Creating an "indexer" service:**
```bash
# Create directories
mkdir -p app/indexer/{domain-public,domain-private,api,services,server}

# Create BUILD.bazel files with appropriate content
# (Follow steps 2-6 above, replacing <service-name> with "indexer")

# Add your Scala code
echo 'case class Document(id: String, content: String)' > app/indexer/domain-public/Document.scala

# Build the service
bazel build //app/indexer/...
```

**Testing the Service Structure:**
```bash
# Build all layers
bazel build //app/<service-name>/...

# Build specific layer
bazel build //app/<service-name>/api

# Query dependencies
bazel query "deps(//app/<service-name>/server)"
```

### Adding a New Dependency

1. **Find dependency on Maven Central**
   - Search Maven Central for the library
   - Verify Scala 3 version compatibility (must be Scala 3.3.7 for this project)
   - Note the exact coordinates: `group:artifact:version`

2. **If you cannot find the correct version:**
   - Ask the user: "I'm stuck finding dependency XYZ. Could you provide me with a Maven Central link?"
   - User can provide a link like: `https://mvnrepository.com/artifact/org.virtuslab/besom-aws_3/7.7.0-core.0.5/`
   - Extract coordinates from URL path: `org.virtuslab:besom-aws_3:7.7.0-core.0.5`

3. Add to MODULE.bazel in `maven.install()` artifacts list

4. Run `bazel mod deps` to update dependency graph

5. **CRITICAL: Always test build after adding dependencies:**
   ```bash
   bazel build //app/...
   ```
   This verifies:
   - Dependencies resolve correctly
   - Versions exist on Maven Central
   - No conflicts with existing dependencies

6. If build fails, check error messages for:
   - "not found" errors → Ask user for Maven Central link to verify version
   - Version conflicts → adjust versions or use `version_conflict_policy`
   - Transitive dependency issues → may need to add explicitly

7. Reference in BUILD.bazel: `@maven//:group_artifact_3`

8. For compile-time dependencies (macros), add explicitly to deps

**Important:** Never add multiple dependencies without testing the build. Add one or a few related dependencies, test, then continue. This makes it easier to identify which dependency caused a problem.

**When Stuck:** Don't guess at versions or give up. Ask the user for the Maven Central link and extract coordinates from it.

### Troubleshooting Build Failures

1. **Read the error message carefully**
   - Bazel errors are usually specific and actionable

2. **Check classpath issues**
   ```bash
   cat bazel-out/k8-fastbuild/bin/path/to/target.params
   ```

3. **Verify dependencies**
   ```bash
   bazel query "deps(//your:target)" --output=label
   ```

4. **Clean and rebuild**
   ```bash
   bazel clean
   bazel build //your:target --verbose_failures
   ```

5. **Check for common issues**
   - TASTy version mismatch (see Section 6)
   - Missing macro dependencies (see Section 2)
   - Incorrect dependency declaration (see Section 3)

## Reference Materials

- `references/bazel-module-setup.md` - MODULE.bazel patterns and configuration
- `references/scala-rules.md` - Scala 3 rules and compiler options
- `references/maven-deps.md` - Maven dependency management in Bazel
- `references/bazel-best-practices.md` - Build optimization and performance

## Scripts

- `scripts/validate-build.sh` - Validate Bazel workspace configuration
- `scripts/init-workspace.sh` - Initialize new Bazel workspace with MODULE.bazel

## Additional Resources

For detailed documentation on specific patterns in this project, see:
- `docs/BAZEL_SCALA_SETUP.md` - Project-specific Bazel + Scala 3 setup guide
- `defs.bzl` - Custom macro definitions
- `MODULE.bazel` - Current dependency configuration

## Important Reminders

1. **Always use MODULE.bazel**, not WORKSPACE (modern Bazel 7.0+ approach)
2. **Match Scala versions exactly** - TASTy is not backwards compatible
3. **Include macro dependencies explicitly** - Bazel doesn't infer them
4. **Use custom macros** when available to reduce boilerplate
5. **Verify on Maven Central** before adding new dependencies
6. **Test incrementally** - build after each significant change
7. **Read error messages** - Bazel is usually specific about what's wrong
