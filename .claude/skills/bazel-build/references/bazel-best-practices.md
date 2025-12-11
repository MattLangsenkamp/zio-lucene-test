# Bazel Best Practices

Optimization techniques and best practices for Bazel builds.

## Build Performance

### Local Caching

Enable disk caching for faster rebuilds:

```bash
# One-time cache
bazel build //... --disk_cache=/tmp/bazel_cache

# Persistent configuration
# Add to .bazelrc:
build --disk_cache=~/.cache/bazel
```

**Benefits:**
- Reuse build artifacts across workspace changes
- Faster rebuilds after `bazel clean`
- Share cache across git branches

### Repository Cache

Cache external dependencies:

```bash
# Add to .bazelrc
build --repository_cache=~/.cache/bazel/repos
```

**Benefits:**
- Avoid re-downloading Maven dependencies
- Faster clean builds
- Offline builds possible after first fetch

### Build Parallelism

Control parallel build jobs:

```bash
# Auto-detect based on CPU cores
bazel build //... --jobs=auto

# Manual control
bazel build //... --jobs=8

# Add to .bazelrc
build --jobs=auto
```

**Guidelines:**
- `auto` usually optimal
- Reduce for memory-constrained builds
- Increase for powerful machines

### Remote Caching

Share build artifacts across team:

```bash
# Setup remote cache
build --remote_cache=https://cache.example.com
build --remote_upload_local_results=true
```

**Benefits:**
- Team shares build artifacts
- CI builds populate cache for developers
- Massive speedup for large teams

**Setup options:**
- Google Cloud Storage
- AWS S3
- Self-hosted Bazel Remote Cache
- BuildBuddy, BuildBarn, etc.

### Remote Execution

Execute builds on remote servers:

```bash
build --remote_executor=grpc://executor.example.com
```

**Benefits:**
- Consistent build environment
- Leverage powerful build servers
- Parallel execution across machines

## Build Configuration

### .bazelrc File

Centralize build settings:

```bash
# .bazelrc
# Common settings for all commands
common --enable_platform_specific_config

# Build settings
build --jobs=auto
build --disk_cache=~/.cache/bazel
build --repository_cache=~/.cache/bazel/repos
build --verbose_failures

# Test settings
test --test_output=errors
test --test_summary=detailed

# Scala-specific
build --strategy=Scalac=worker
build --worker_max_instances=4

# Platform-specific (applied automatically)
build:linux --sandbox_writable_path=/tmp
build:macos --sandbox_writable_path=/var/tmp
```

### User-Specific Settings

Override with `.bazelrc.user` (gitignored):

```bash
# .gitignore
.bazelrc.user

# .bazelrc.user
build --remote_cache=https://my-cache.example.com
build --jobs=16
```

### Build Modes

Define custom build modes:

```bash
# .bazelrc
# Fast mode - skip tests
build:fast --build_tests_only=false

# Strict mode - all warnings as errors
build:strict --javacopt="-Werror"
build:strict --scalacopts="-Xfatal-warnings"

# Debug mode
build:debug --compilation_mode=dbg
build:debug --strip=never

# Release mode
build:release --compilation_mode=opt
build:release --stamp
```

**Usage:**
```bash
bazel build --config=fast //...
bazel build --config=release //services:app
```

## Target Organization

### Keep Targets Small

**Bad:**
```python
# One giant target
scala_library(
    name = "everything",
    srcs = glob(["**/*.scala"]),  # 500 files
)
```

**Good:**
```python
# Multiple focused targets
scala_library(
    name = "core",
    srcs = glob(["core/**/*.scala"]),
)

scala_library(
    name = "api",
    srcs = glob(["api/**/*.scala"]),
    deps = [":core"],
)
```

**Benefits:**
- Better caching (less rebuilding)
- Faster parallel builds
- Clearer dependencies

### Minimize Dependencies

**Bad:**
```python
scala_library(
    name = "utils",
    deps = [
        "//everything:else",  # Too broad
    ],
)
```

**Good:**
```python
scala_library(
    name = "utils",
    deps = [
        "//core:types",  # Specific
        "//common:helpers",
    ],
)
```

**Benefits:**
- Smaller dependency graphs
- Less rebuilding on changes
- Clearer architecture

### Use Visibility

Control target access:

```python
# Internal implementation
scala_library(
    name = "internal",
    visibility = ["//visibility:private"],
)

# Package-level API
scala_library(
    name = "api",
    visibility = ["//services:__pkg__"],
)

# Public interface
scala_library(
    name = "public",
    visibility = ["//visibility:public"],
)
```

**Benefits:**
- Prevents unintended dependencies
- Clearer API boundaries
- Easier refactoring

## Dependency Management

### Group Common Dependencies

```python
# defs.bzl
ZIO_DEPS = [
    "@maven//:dev_zio_zio_3",
    "@maven//:dev_zio_izumi_reflect_3",
    # ... other ZIO deps
]

LUCENE_DEPS = [
    "@maven//:org_apache_lucene_lucene_core",
    "@maven//:org_apache_lucene_lucene_queryparser",
]

TEST_DEPS = [
    "@maven//:dev_zio_zio_test_3",
    "@maven//:dev_zio_zio_test_sbt_3",
]
```

**Usage:**
```python
load("//:defs.bzl", "ZIO_DEPS", "LUCENE_DEPS")

scala_library(
    name = "search",
    deps = ZIO_DEPS + LUCENE_DEPS,
)
```

**Benefits:**
- Consistency across targets
- Easy to update versions
- Self-documenting

### Explicit Over Implicit

**Bad:**
```python
deps = [
    "//everything",  # Pulls in everything transitively
]
```

**Good:**
```python
deps = [
    "//core:types",
    "//core:utils",
    "@maven//:dev_zio_zio_3",
]
```

**Benefits:**
- Clear what's actually needed
- Easier to remove unused deps
- Better build performance

## Build Strategies

### Workers

Use persistent workers for Scala compilation:

```bash
# .bazelrc
build --strategy=Scalac=worker
build --worker_max_instances=4
```

**Benefits:**
- Reuse JVM between compilations
- Avoid JVM startup cost
- Faster incremental builds

**Tuning:**
- `--worker_max_instances` controls parallelism
- Higher = more memory, more parallelism
- Lower = less memory, less parallelism

### Sandboxing

Control build sandboxing:

```bash
# Strict sandbox (default, recommended)
build --spawn_strategy=sandboxed

# Allow specific writable paths
build --sandbox_writable_path=/tmp

# Disable for troubleshooting only
build --spawn_strategy=local
```

**Benefits:**
- Hermetic builds
- Catches missing dependencies
- Reproducible across machines

## Debugging Builds

### Verbose Output

```bash
# Show full compilation commands
bazel build //... --subcommands

# Detailed failure messages
bazel build //... --verbose_failures

# Show execution log
bazel build //... --execution_log_json_file=exec.json
```

### Analyze Rebuild Reasons

```bash
# Explain why rebuild happened
bazel build //target --explain=explain.txt

# Verbose explanation
bazel build //target --explain=explain.txt --verbose_explanations
```

### Dependency Analysis

```bash
# Show dependency graph
bazel query "deps(//target)" --output=graph > deps.dot
dot -Tpng deps.dot > deps.png

# Find circular dependencies
bazel query "somepath(//a, //a)"

# Show why target depends on another
bazel query "somepath(//start, //end)"
```

### Action Inspection

```bash
# Show actions for a target
bazel aquery //target

# Show specific action type
bazel aquery 'mnemonic("Scalac", //...)'

# Show full command lines
bazel aquery //target --output=text
```

## Testing Best Practices

### Test Sizing

```python
# Quick unit tests
scala_test(
    name = "unit_test",
    size = "small",  # < 60s
)

# Integration tests
scala_test(
    name = "integration_test",
    size = "medium",  # < 300s
)

# System tests
scala_test(
    name = "system_test",
    size = "large",  # < 900s
)
```

**Benefits:**
- Bazel enforces timeouts
- Parallel test execution optimized
- Clear test categorization

### Test Tagging

```python
scala_test(
    name = "test",
    tags = [
        "unit",
        "fast",
        "no-sandbox",  # Disable sandbox if needed
    ],
)
```

**Usage:**
```bash
# Run only unit tests
bazel test --test_tag_filters=unit //...

# Exclude slow tests
bazel test --test_tag_filters=-slow //...
```

### Test Output

```bash
# .bazelrc
test --test_output=errors  # Only show failures
test --test_summary=detailed  # Detailed summary

# Override for debugging
bazel test --test_output=all //target
```

## Code Organization

### Directory Structure

```
project/
├── MODULE.bazel           # Dependency configuration
├── BUILD.bazel            # Root build file
├── .bazelrc               # Build settings
├── .bazelrc.user          # User-specific (gitignored)
├── defs.bzl               # Custom macros
├── core/
│   ├── BUILD.bazel
│   └── src/
│       └── main/scala/
├── api/
│   ├── BUILD.bazel
│   └── src/
│       └── main/scala/
└── services/
    ├── BUILD.bazel
    └── src/
        └── main/scala/
```

### BUILD File Patterns

```python
# Load definitions
load("@rules_scala//scala:scala.bzl", "scala_library")
load("//:defs.bzl", "ZIO_DEPS")

# Package-level default visibility
package(default_visibility = ["//visibility:private"])

# File groups
filegroup(
    name = "all_sources",
    srcs = glob(["**/*.scala"]),
)

# Libraries
scala_library(
    name = "lib",
    srcs = glob(["**/*.scala"]),
    deps = ZIO_DEPS,
    visibility = ["//visibility:public"],
)

# Tests
scala_test(
    name = "lib_test",
    srcs = glob(["**/*Test.scala"]),
    deps = [":lib"],
)
```

## Custom Macros

### When to Create Macros

Create macros for:
- Repeated target patterns
- Project-specific conventions
- Complex dependency sets
- Enforcing standards

### Example: ZIO Library Macro

```python
# defs.bzl
load("@rules_scala//scala:scala.bzl", "scala_library")

ZIO_DEPS = [
    "@maven//:dev_zio_zio_3",
    "@maven//:dev_zio_izumi_reflect_3",
    "@maven//:dev_zio_izumi_reflect_thirdparty_boopickle_shaded_3",
    "@maven//:dev_zio_zio_stacktracer_3",
    "@maven//:dev_zio_zio_internal_macros_3",
]

SCALA_OPTS = [
    "-encoding", "utf-8",
    "-feature",
    "-unchecked",
    "-deprecation",
]

def zio_scala_library(name, deps = [], scalacopts = [], **kwargs):
    """Scala library with ZIO dependencies and project defaults."""
    scala_library(
        name = name,
        deps = ZIO_DEPS + deps,
        scalacopts = SCALA_OPTS + scalacopts,
        **kwargs
    )
```

**Usage:**
```python
load("//:defs.bzl", "zio_scala_library")

zio_scala_library(
    name = "service",
    srcs = glob(["*.scala"]),
    deps = ["//core:api"],
)
```

## CI/CD Integration

### GitHub Actions Example

```yaml
# .github/workflows/build.yml
name: Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Mount bazel cache
        uses: actions/cache@v3
        with:
          path: ~/.cache/bazel
          key: bazel-${{ runner.os }}-${{ hashFiles('MODULE.bazel') }}

      - name: Build
        run: bazel build //...

      - name: Test
        run: bazel test //...

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-results
          path: bazel-testlogs/**/*.xml
```

### Bazelisk

Use Bazelisk for consistent Bazel versions:

```bash
# Install
npm install -g @bazel/bazelisk

# Or download binary
wget https://github.com/bazelbuild/bazelisk/releases/download/v1.19.0/bazelisk-linux-amd64
chmod +x bazelisk-linux-amd64
sudo mv bazelisk-linux-amd64 /usr/local/bin/bazel

# .bazelversion file
echo "7.0.0" > .bazelversion
```

**Benefits:**
- Automatic Bazel version management
- Consistent across team
- Easy version upgrades

## Performance Monitoring

### Build Event Protocol

Track build metrics:

```bash
bazel build //... --build_event_json_file=build_events.json
```

Analyze:
- Build times
- Cache hit rates
- Action counts
- Critical path

### Profiling

```bash
# Generate profile
bazel build //... --profile=profile.gz

# Analyze with Bazel's analyzer
bazel analyze-profile profile.gz

# Or use web UI
bazel analyze-profile profile.gz --html_details
```

**Look for:**
- Slow actions
- Cache misses
- Bottlenecks in dependency graph

## Common Pitfalls

### 1. Overly Broad Dependencies

**Problem:** Depending on large targets
**Solution:** Depend on specific, small targets

### 2. Missing Visibility

**Problem:** Everything public by default
**Solution:** Use package-private or explicit visibility

### 3. No Lock Files

**Problem:** Non-reproducible builds
**Solution:** Always use Maven lock files

### 4. Ignoring Cache

**Problem:** Slow rebuilds
**Solution:** Enable disk and repository caching

### 5. Large Source Globs

**Problem:** `glob(["**/*.scala"])` includes too much
**Solution:** Be specific, use exclusions

### 6. Not Using Workers

**Problem:** Slow Scala compilation
**Solution:** Enable Scalac workers

### 7. No Build Configuration

**Problem:** Typing flags every time
**Solution:** Use .bazelrc

### 8. Poor Target Granularity

**Problem:** Monolithic targets
**Solution:** Split into focused components

## Checklist: Optimal Build Setup

- [ ] .bazelrc configured with caching
- [ ] Bazelisk for version management
- [ ] Maven lock files committed
- [ ] Scalac workers enabled
- [ ] Targets are small and focused
- [ ] Visibility controls in place
- [ ] Custom macros for common patterns
- [ ] CI/CD caching configured
- [ ] .bazelversion file present
- [ ] .bazelrc.user gitignored

## Resources

- [Bazel Performance Guide](https://bazel.build/configure/performance)
- [Bazel Best Practices](https://bazel.build/configure/best-practices)
- [Remote Caching Setup](https://bazel.build/remote/caching)
- [Build Event Protocol](https://bazel.build/remote/bep)
