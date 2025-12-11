# Maven Dependency Management in Bazel

Guide to managing Maven dependencies in Bazel using rules_jvm_external.

## Overview

Bazel uses `rules_jvm_external` to fetch and manage Maven dependencies:
- Automatic transitive dependency resolution
- Version conflict management
- Reproducible builds with lock files
- Integration with Maven Central and private repositories

## Basic Setup

### Maven Extension Configuration

```python
# MODULE.bazel
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")

maven.install(
    artifacts = [
        "group:artifact:version",
        # ... more dependencies
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    lock_file = "//:maven_install.json",
)

use_repo(maven, "maven")
```

## Adding Dependencies

### Finding Dependencies

**Maven Central Search:**
1. Visit https://central.sonatype.com/
2. Search for library name
3. Find correct group:artifact coordinates
4. Check Scala version compatibility

**Example: Adding ZIO**
```
Search: "zio"
Find: dev.zio:zio_3:2.1.17
       ^^     ^^ ^^  ^^^^^^
       |      |  |   version
       |      |  Scala 3 suffix
       |      artifact
       group
```

### Adding to MODULE.bazel

```python
maven.install(
    artifacts = [
        # Existing dependencies...

        # New dependency
        "dev.zio:zio_3:2.1.17",
    ],
)
```

### Updating Lock File

After modifying MODULE.bazel:

```bash
bazel run @maven//:pin
```

This updates `maven_install.json` with exact resolved versions.

### Using in BUILD Files

Convert Maven coordinates to Bazel label:

```
group:artifact:version → @maven//:group_artifact

Examples:
dev.zio:zio_3:2.1.17
  → @maven//:dev_zio_zio_3

org.apache.lucene:lucene-core:9.12.0
  → @maven//:org_apache_lucene_lucene_core

com.typesafe.akka:akka-actor_3:2.6.20
  → @maven//:com_typesafe_akka_akka_actor_3
```

**Rules:**
- Replace `:` and `-` with `_`
- Replace `.` in group with `_`
- Omit version
- Preserve `_3` or `_2.13` suffixes

**BUILD file usage:**
```python
scala_library(
    name = "mylib",
    srcs = glob(["*.scala"]),
    deps = [
        "@maven//:dev_zio_zio_3",
    ],
)
```

## Dependency Versions

### Explicit Versions

```python
artifacts = [
    "dev.zio:zio_3:2.1.17",  # Exact version
]
```

### Version Ranges

```python
artifacts = [
    "dev.zio:zio_3:[2.1.0,2.2.0)",  # >= 2.1.0, < 2.2.0
]
```

**Range syntax:**
- `[` - Inclusive
- `(` - Exclusive
- `[1.0,2.0)` - >= 1.0, < 2.0
- `[1.0,)` - >= 1.0, any later version

**Best practice:** Use explicit versions for reproducibility.

### Version Properties

Define versions in one place:

```python
# MODULE.bazel
ZIO_VERSION = "2.1.17"
LUCENE_VERSION = "9.12.0"

maven.install(
    artifacts = [
        "dev.zio:zio_3:" + ZIO_VERSION,
        "dev.zio:zio-streams_3:" + ZIO_VERSION,
        "org.apache.lucene:lucene-core:" + LUCENE_VERSION,
    ],
)
```

## Transitive Dependencies

### Automatic Resolution

Bazel automatically fetches transitive dependencies:

```python
# You add:
artifacts = ["dev.zio:zio_3:2.1.17"]

# Bazel also fetches (automatically):
# - dev.zio:izumi-reflect_3:2.3.10
# - scala-library_3:3.3.x
# - etc.
```

### Viewing Transitive Dependencies

```bash
# Show all dependencies of a target
bazel query "deps(@maven//:dev_zio_zio_3)"

# Show dependency tree
bazel query "deps(@maven//:dev_zio_zio_3)" --output=graph

# Filter for specific dependency
bazel query "deps(@maven//:dev_zio_zio_3)" | grep izumi
```

### Excluding Transitive Dependencies

```python
maven.install(
    artifacts = [
        maven.artifact(
            group = "dev.zio",
            artifact = "zio_3",
            version = "2.1.17",
            exclusions = [
                "org.scala-lang:scala-library",  # Exclude specific dep
            ],
        ),
    ],
)
```

**When to exclude:**
- Dependency conflicts
- Replacing with compatible alternative
- Dependency not needed

## Version Conflicts

### Detecting Conflicts

Multiple dependencies requiring different versions:

```python
artifacts = [
    "com.lib:a:1.0",  # Depends on com.common:util:1.0
    "com.lib:b:2.0",  # Depends on com.common:util:2.0
]
```

Bazel will resolve to one version (usually latest).

### Conflict Resolution Strategy

```python
maven.install(
    artifacts = [...],
    version_conflict_policy = "pinned",
)
```

**Policies:**
- `"pinned"` - Use versions from lock file (recommended)
- `"default"` - Bazel's default resolution (highest version wins)

### Manual Override

Force a specific version:

```python
maven.install(
    artifacts = [
        maven.artifact(
            group = "com.common",
            artifact = "util",
            version = "2.0",  # Force this version
        ),
    ],
    override_targets = {
        "com.common:util": "@maven//:com_common_util",
    },
)
```

## Repository Configuration

### Maven Central (Default)

```python
maven.install(
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)
```

### Multiple Repositories

```python
maven.install(
    repositories = [
        "https://repo1.maven.org/maven2",              # Maven Central
        "https://oss.sonatype.org/content/repositories/snapshots",  # Snapshots
        "https://jitpack.io",                          # JitPack
        "https://maven.pkg.github.com/org/repo",       # GitHub Packages
    ],
)
```

**Repository order matters:** Earlier repositories are checked first.

### Private Repositories

#### Authentication with .netrc

Create `~/.netrc`:
```
machine maven.pkg.github.com
login your-username
password ghp_yourtoken
```

#### Authentication with environment variables

```bash
export MAVEN_USER=your-username
export MAVEN_PASSWORD=your-token
```

Reference in MODULE.bazel:
```python
maven.install(
    repositories = [
        "https://maven.pkg.github.com/org/repo",
    ],
    # Authentication handled by .netrc or env vars
)
```

## Lock Files

### Purpose

Lock files ensure reproducible builds:
- Pin exact dependency versions
- Cache dependency resolution
- Track changes in version control

### Generating Lock File

```bash
bazel run @maven//:pin
```

Creates `maven_install.json` with:
- Exact versions of all dependencies
- Transitive dependency tree
- SHA checksums for verification

### Using Lock File

```python
maven.install(
    artifacts = [...],
    lock_file = "//:maven_install.json",  # Path to lock file
)
```

### Updating Lock File

After changing dependencies:

```bash
# Modify MODULE.bazel
# Then regenerate lock file
bazel run @maven//:pin
```

Commit both `MODULE.bazel` and `maven_install.json`.

### Lock File Conflicts

**In version control:**
- Resolve MODULE.bazel conflict first
- Run `bazel run @maven//:pin` to regenerate lock file
- Commit resolved MODULE.bazel and new lock file

## Scala-Specific Patterns

### Scala 3 Dependencies

All Scala 3 dependencies use `_3` suffix:

```python
artifacts = [
    "org.scala-lang:scala3-library_3:3.3.7",
    "dev.zio:zio_3:2.1.17",
    "com.softwaremill.sttp.tapir:tapir-core_3:1.11.10",
]
```

### TASTy Compatibility

**Critical:** Scala 3 TASTy is not backwards compatible.

```python
# All Scala 3 deps must match compiler version
artifacts = [
    "org.scala-lang:scala3-library_3:3.3.7",  # Compiler version
    "dev.zio:zio_3:2.1.17",  # Must be compiled with 3.3.x
]
```

**Verification steps:**
1. Check library on Maven Central
2. Look for "compiled with Scala X.Y.Z"
3. Verify TASTy version compatibility
4. Update compiler if needed

### Cross-Version Dependencies

```python
# Scala 3 library depending on Java library (OK)
deps = [
    "@maven//:dev_zio_zio_3",
    "@maven//:org_apache_lucene_lucene_core",  # No Scala suffix
]

# Scala 3 library depending on Scala 2 library (AVOID)
deps = [
    "@maven//:dev_zio_zio_3",
    "@maven//:com_typesafe_akka_akka_actor_2_13",  # Incompatible!
]
```

## Common Dependency Patterns

### ZIO Ecosystem

```python
artifacts = [
    # Core
    "dev.zio:zio_3:2.1.17",

    # Streams
    "dev.zio:zio-streams_3:2.1.17",

    # HTTP
    "dev.zio:zio-http_3:3.0.1",

    # Kafka
    "dev.zio:zio-kafka_3:2.9.0",

    # JSON
    "dev.zio:zio-json_3:0.7.3",

    # Config
    "dev.zio:zio-config_3:4.0.2",

    # Testing
    "dev.zio:zio-test_3:2.1.17",
    "dev.zio:zio-test-sbt_3:2.1.17",
]
```

### Lucene Search

```python
artifacts = [
    "org.apache.lucene:lucene-core:9.12.0",
    "org.apache.lucene:lucene-queryparser:9.12.0",
    "org.apache.lucene:lucene-analyzers-common:9.12.0",
    "org.apache.lucene:lucene-highlighter:9.12.0",
]
```

### Testing Libraries

```python
artifacts = [
    # ZIO Test
    "dev.zio:zio-test_3:2.1.17",
    "dev.zio:zio-test-sbt_3:2.1.17",

    # ScalaTest (alternative)
    "org.scalatest:scalatest_3:3.2.19",

    # Mocking
    "org.scalamock:scalamock_3:6.0.0",
]
```

### Logging

```python
artifacts = [
    # SLF4J API
    "org.slf4j:slf4j-api:2.0.16",

    # Logback implementation
    "ch.qos.logback:logback-classic:1.5.12",
    "ch.qos.logback:logback-core:1.5.12",

    # ZIO Logging
    "dev.zio:zio-logging_3:2.3.2",
]
```

## Advanced Patterns

### Classifier Dependencies

For architecture-specific or special builds:

```python
maven.install(
    artifacts = [
        maven.artifact(
            group = "io.netty",
            artifact = "netty-transport-native-epoll",
            version = "4.1.100.Final",
            classifier = "linux-x86_64",
        ),
    ],
)
```

### Packaging Type

For non-JAR artifacts:

```python
maven.install(
    artifacts = [
        maven.artifact(
            group = "com.example",
            artifact = "lib",
            version = "1.0",
            packaging = "aar",  # Android Archive
        ),
    ],
)
```

### Dependency Substitution

Replace a dependency with a local version:

```python
maven.install(
    artifacts = [...],
    override_targets = {
        "com.example:lib": "//local:lib",  # Use local target instead
    },
)
```

## Querying Dependencies

### List All Maven Dependencies

```bash
bazel query @maven//...
```

### Find Specific Dependency

```bash
bazel query @maven//... | grep zio
```

### Show Dependency Details

```bash
bazel query @maven//:dev_zio_zio_3 --output=build
```

### Dependency Graph

```bash
bazel query "deps(@maven//:dev_zio_zio_3)" --output=graph > deps.dot
dot -Tpng deps.dot > deps.png
```

### Reverse Dependencies

Find what depends on a library:

```bash
bazel query "rdeps(//..., @maven//:dev_zio_zio_3)"
```

## Troubleshooting

### Issue: Dependency not found

**Cause:** Incorrect coordinates or not on repository

**Solution:**
1. Verify coordinates on Maven Central
2. Check spelling and version
3. Ensure repository is listed
4. Try without lock file first
5. Regenerate lock file

### Issue: Version conflict

**Cause:** Multiple versions required

**Solution:**
```bash
# Find conflict
bazel query "deps(//..., @maven//:conflicting_lib)"

# Force version in MODULE.bazel
maven.install(
    artifacts = [
        "com.lib:conflicting:2.0",  # Force this version
    ],
    version_conflict_policy = "pinned",
)
```

### Issue: Lock file errors

**Cause:** Lock file out of sync

**Solution:**
```bash
# Delete lock file
rm maven_install.json

# Regenerate
bazel run @maven//:pin
```

### Issue: Transitive dependency missing

**Cause:** Exclusion or version conflict

**Solution:**
```bash
# Check what's pulling it in
bazel query "deps(@maven//:your_target)" | grep missing_dep

# Add explicitly
maven.install(
    artifacts = [
        "com.missing:dep:1.0",
    ],
)
```

## Best Practices

1. **Always use lock files**
   - Ensures reproducible builds
   - Faster dependency resolution

2. **Pin exact versions**
   - Avoid version ranges
   - Explicit is better than implicit

3. **Group related dependencies**
   - Comment purpose of each group
   - Keep related versions in sync

4. **Verify Scala versions**
   - Check TASTy compatibility
   - Ensure `_3` suffix for Scala 3

5. **Commit lock files**
   - Track in version control
   - Update atomically with MODULE.bazel

6. **Document version choices**
   - Comment why specific versions chosen
   - Note compatibility constraints

7. **Minimize exclusions**
   - Only exclude when necessary
   - Document reason for exclusion

8. **Test after changes**
   - Build all targets
   - Run tests
   - Verify no conflicts

## Example: Complete Maven Configuration

```python
# MODULE.bazel
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")

# Define version constants
ZIO_VERSION = "2.1.17"
LUCENE_VERSION = "9.12.0"

maven.install(
    artifacts = [
        # Scala 3 standard library
        "org.scala-lang:scala3-library_3:3.3.7",

        # ZIO ecosystem
        "dev.zio:zio_3:" + ZIO_VERSION,
        "dev.zio:zio-streams_3:" + ZIO_VERSION,
        "dev.zio:zio-http_3:3.0.1",

        # ZIO macro dependencies (required for compile)
        "dev.zio:izumi-reflect_3:2.3.10",
        "dev.zio:izumi-reflect-thirdparty-boopickle-shaded_3:2.3.10",
        "dev.zio:zio-stacktracer_3:" + ZIO_VERSION,
        "dev.zio:zio-internal-macros_3:" + ZIO_VERSION,

        # Lucene
        "org.apache.lucene:lucene-core:" + LUCENE_VERSION,
        "org.apache.lucene:lucene-queryparser:" + LUCENE_VERSION,

        # Testing
        "dev.zio:zio-test_3:" + ZIO_VERSION,
        "dev.zio:zio-test-sbt_3:" + ZIO_VERSION,
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    lock_file = "//:maven_install.json",
    version_conflict_policy = "pinned",
)

use_repo(maven, "maven")
```

## References

- [rules_jvm_external Documentation](https://github.com/bazelbuild/rules_jvm_external)
- [Maven Central](https://central.sonatype.com/)
- [Maven Coordinates Specification](https://maven.apache.org/pom.html#Maven_Coordinates)
