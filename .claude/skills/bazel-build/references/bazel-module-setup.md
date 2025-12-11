# Bazel MODULE.bazel Setup Guide

Modern Bazel (7.0+) uses MODULE.bazel for dependency management instead of legacy WORKSPACE files.

## Overview

MODULE.bazel provides:
- **Automatic transitive dependency resolution** - Dependencies of dependencies are handled automatically
- **Version conflict resolution** - Bazel resolves version conflicts using a consistent algorithm
- **Cleaner syntax** - More readable and maintainable than WORKSPACE
- **Better reproducibility** - Lockfile (MODULE.bazel.lock) ensures consistent builds

## Basic Structure

```python
# MODULE.bazel
module(
    name = "your-project",
    version = "1.0.0",
)

# Bazel dependencies (rules)
bazel_dep(name = "rules_scala", version = "6.6.0")
bazel_dep(name = "rules_jvm_external", version = "6.5.0")

# Maven dependencies
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")

maven.install(
    artifacts = [
        "org.scala-lang:scala3-library_3:3.3.7",
        "dev.zio:zio_3:2.1.17",
        # ... more dependencies
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    lock_file = "//:maven_install.json",
)

use_repo(maven, "maven")
```

## Scala 3 Configuration

### Required Dependencies

```python
bazel_dep(name = "rules_scala", version = "6.6.0")
bazel_dep(name = "rules_jvm_external", version = "6.5.0")
bazel_dep(name = "rules_java", version = "7.12.2")
```

### Scala Rules Setup

```python
scala = use_extension("@rules_scala//scala:extensions.bzl", "scala")

# Register Scala 3 toolchain
scala.toolchain(
    name = "scala_3_3_7",
    scala_version = "3.3.7",
)

use_repo(scala, "scala_3_3_7")

# Register the toolchain
register_toolchains(
    "@scala_3_3_7//:all",
)
```

## Maven Dependency Management

### Maven Extension Setup

```python
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")

maven.install(
    name = "maven",
    artifacts = [
        # Your dependencies here
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    # Optional: lock file for reproducibility
    lock_file = "//:maven_install.json",
    # Optional: version conflict resolution
    version_conflict_policy = "pinned",
)

use_repo(maven, "maven")
```

### Adding Maven Dependencies

#### Standard Java/Scala Libraries

```python
artifacts = [
    # Scala 3 standard library
    "org.scala-lang:scala3-library_3:3.3.7",

    # Scala 3 dependencies (note the _3 suffix)
    "dev.zio:zio_3:2.1.17",
    "dev.zio:zio-streams_3:2.1.17",
    "dev.zio:zio-http_3:3.0.1",

    # Java libraries (no suffix)
    "org.apache.lucene:lucene-core:9.12.0",
    "org.apache.lucene:lucene-queryparser:9.12.0",
]
```

#### Dependency Naming Convention

Maven coordinates to Bazel label conversion:
```
group:artifact:version  →  @maven//:group_artifact

Examples:
org.apache.lucene:lucene-core:9.12.0
  → @maven//:org_apache_lucene_lucene_core

dev.zio:zio_3:2.1.17
  → @maven//:dev_zio_zio_3

dev.zio:izumi-reflect_3:2.3.10
  → @maven//:dev_zio_izumi_reflect_3
```

**Important:**
- Replace `:` and `-` with `_` in the label
- Dots `.` in group IDs become underscores `_`
- The version is not included in the label

### Lock File Management

Lock files ensure reproducible builds across machines and time.

**Generate lock file:**
```bash
bazel run @maven//:pin
```

This creates `maven_install.json` with pinned versions.

**Update dependencies:**
1. Modify `MODULE.bazel` artifacts list
2. Run `bazel run @maven//:pin` to update lock file
3. Commit both `MODULE.bazel` and `maven_install.json`

**Benefits:**
- Reproducible builds
- Faster dependency resolution (cached)
- Explicit version tracking in version control

### Version Conflict Resolution

When multiple dependencies require different versions of the same library:

```python
maven.install(
    artifacts = [
        "com.example:lib:1.0",
        "com.example:lib:2.0",  # Conflict!
    ],
    version_conflict_policy = "pinned",  # Use versions from lock file
)
```

**Policies:**
- `"pinned"` - Use versions from lock file (recommended)
- `"default"` - Use Bazel's version resolution algorithm

**Manual override:**
```python
maven.install(
    artifacts = [
        "com.example:lib:2.0",  # Force this version
    ],
    override_targets = {
        "com.example:lib": "@maven//:com_example_lib",
    },
)
```

## Scala 3 + ZIO Specific Patterns

### Complete ZIO Dependencies

ZIO requires several transitive dependencies for macro support:

```python
maven.install(
    artifacts = [
        # Core ZIO
        "dev.zio:zio_3:2.1.17",

        # Required for ZIO macros (compile-time)
        "dev.zio:izumi-reflect_3:2.3.10",
        "dev.zio:izumi-reflect-thirdparty-boopickle-shaded_3:2.3.10",
        "dev.zio:zio-stacktracer_3:2.1.17",
        "dev.zio:zio-internal-macros_3:2.1.17",

        # Optional ZIO modules
        "dev.zio:zio-streams_3:2.1.17",
        "dev.zio:zio-http_3:3.0.1",
        "dev.zio:zio-kafka_3:2.9.0",
    ],
)
```

**Why these dependencies?**
- `izumi-reflect*` - Runtime type information for ZIO macros
- `zio-stacktracer` - Enhanced stack traces for ZIO effects
- `zio-internal-macros` - Macro implementation internals

### TASTy Version Compatibility

**Critical:** Scala 3 TASTy format is version-specific.

```python
# All Scala 3 dependencies must match the compiler version
artifacts = [
    "org.scala-lang:scala3-library_3:3.3.7",
    "dev.zio:zio_3:2.1.17",  # Must be compiled with Scala 3.3.x
]
```

**Verification:**
1. Check dependency on Maven Central
2. Look for "compiled with Scala 3.3.x" in description
3. Verify TASTy version matches

**If versions don't match:**
- Update Scala compiler version in MODULE.bazel
- Find library versions compatible with your Scala version
- Use lock file to enforce consistency

## Repository Configuration

### Multiple Maven Repositories

```python
maven.install(
    artifacts = [...],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://oss.sonatype.org/content/repositories/snapshots",  # For snapshots
        "https://maven.pkg.github.com/your-org/your-repo",  # Private repo
    ],
)
```

### Authentication (Private Repositories)

Create `.netrc` file in home directory:
```
machine maven.pkg.github.com
login your-username
password your-token
```

Or use environment variables:
```bash
export MAVEN_USER=your-username
export MAVEN_PASSWORD=your-token
```

## Module Extensions

### Custom Extensions

```python
# Load custom extension
my_ext = use_extension("//tools:extensions.bzl", "my_extension")

my_ext.configure(
    setting = "value",
)

use_repo(my_ext, "my_repo")
```

## Best Practices

### 1. Use Lock Files
Always use lock files for reproducibility:
```python
maven.install(
    lock_file = "//:maven_install.json",
)
```

### 2. Pin Bazel Dependencies
Specify exact versions for rules:
```python
bazel_dep(name = "rules_scala", version = "6.6.0")  # Not "latest"
```

### 3. Group Related Dependencies
Organize artifacts by purpose:
```python
maven.install(
    artifacts = [
        # Scala core
        "org.scala-lang:scala3-library_3:3.3.7",

        # ZIO ecosystem
        "dev.zio:zio_3:2.1.17",
        "dev.zio:zio-streams_3:2.1.17",

        # Lucene
        "org.apache.lucene:lucene-core:9.12.0",
        "org.apache.lucene:lucene-queryparser:9.12.0",
    ],
)
```

### 4. Document Version Constraints
Add comments explaining version choices:
```python
artifacts = [
    # ZIO 2.1.17 - minimum version for Scala 3.3.7 TASTy compatibility
    "dev.zio:zio_3:2.1.17",
]
```

### 5. Validate Configuration
After changes, validate:
```bash
# Update dependency graph
bazel mod deps

# Verify dependencies
bazel query @maven//... | grep <package>

# Test build
bazel build //...
```

## Troubleshooting

### Issue: "No such module" error

**Cause:** `bazel_dep` not found or wrong version

**Solution:**
1. Check module name and version on Bazel Central Registry
2. Ensure Bazel version supports the module version
3. Update Bazel if necessary: `bazelisk use latest`

### Issue: Maven dependency not found

**Cause:** Incorrect artifact coordinates or repository

**Solution:**
1. Verify artifact exists on Maven Central
2. Check spelling of group:artifact:version
3. Ensure repository is listed in `repositories = [...]`
4. Check network access to repository

### Issue: Lock file out of sync

**Cause:** `MODULE.bazel` changed but lock file not updated

**Solution:**
```bash
bazel run @maven//:pin
```

### Issue: Version conflicts

**Cause:** Multiple dependencies require different versions

**Solution:**
1. Check conflict: `bazel query "deps(@maven//:your_target)"`
2. Use `version_conflict_policy = "pinned"`
3. Manually override if needed
4. Update lock file

## Migration from WORKSPACE

If migrating from legacy WORKSPACE:

1. **Create MODULE.bazel**
   ```python
   module(name = "your-project", version = "1.0.0")
   ```

2. **Convert maven_install to maven extension**
   ```python
   # Old WORKSPACE:
   maven_install(
       artifacts = [...],
   )

   # New MODULE.bazel:
   maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
   maven.install(artifacts = [...])
   use_repo(maven, "maven")
   ```

3. **Convert http_archive to bazel_dep**
   ```python
   # Old WORKSPACE:
   http_archive(name = "rules_scala", ...)

   # New MODULE.bazel:
   bazel_dep(name = "rules_scala", version = "6.6.0")
   ```

4. **Test migration**
   ```bash
   bazel build //...
   ```

5. **Remove WORKSPACE file** once verified

## Example: Complete MODULE.bazel for Scala 3 + ZIO

```python
module(
    name = "zio-lucene",
    version = "1.0.0",
)

# Bazel rules
bazel_dep(name = "rules_scala", version = "6.6.0")
bazel_dep(name = "rules_jvm_external", version = "6.5.0")
bazel_dep(name = "rules_java", version = "7.12.2")

# Scala toolchain
scala = use_extension("@rules_scala//scala:extensions.bzl", "scala")
scala.toolchain(name = "scala_3_3_7", scala_version = "3.3.7")
use_repo(scala, "scala_3_3_7")
register_toolchains("@scala_3_3_7//:all")

# Maven dependencies
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        "org.scala-lang:scala3-library_3:3.3.7",
        "dev.zio:zio_3:2.1.17",
        "dev.zio:izumi-reflect_3:2.3.10",
        "dev.zio:izumi-reflect-thirdparty-boopickle-shaded_3:2.3.10",
        "dev.zio:zio-stacktracer_3:2.1.17",
        "dev.zio:zio-internal-macros_3:2.1.17",
        "dev.zio:zio-streams_3:2.1.17",
        "org.apache.lucene:lucene-core:9.12.0",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    lock_file = "//:maven_install.json",
)
use_repo(maven, "maven")
```

## References

- [Bazel MODULE.bazel Documentation](https://bazel.build/external/module)
- [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external)
- [rules_scala](https://github.com/bazelbuild/rules_scala)
- [Bazel Central Registry](https://registry.bazel.build/)
