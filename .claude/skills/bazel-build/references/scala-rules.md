# Scala 3 Rules for Bazel

Comprehensive guide to configuring Scala 3 build targets in Bazel.

## Overview

Scala rules in Bazel provide build targets for Scala code:
- `scala_library` - Compile Scala code into JAR
- `scala_binary` - Compile and package executable
- `scala_test` - Compile and run tests
- `scala_import` - Import external Scala JARs

## Basic Target Types

### scala_library

Used for library code that will be depended on by other targets.

```python
load("@rules_scala//scala:scala.bzl", "scala_library")

scala_library(
    name = "core",
    srcs = glob(["src/main/scala/**/*.scala"]),
    deps = [
        "@maven//:dev_zio_zio_3",
        "@maven//:org_apache_lucene_lucene_core",
    ],
    visibility = ["//visibility:public"],
)
```

**Parameters:**
- `name` - Target name (required)
- `srcs` - Source files (required)
- `deps` - Dependencies (libraries, other targets)
- `exports` - Dependencies that consumers also get
- `runtime_deps` - Dependencies only needed at runtime
- `visibility` - Who can depend on this target
- `scalacopts` - Compiler options
- `javac_opts` - Java compiler options (for Java files)
- `resources` - Non-code files to include in JAR

### scala_binary

Used for executable applications with a main method.

```python
load("@rules_scala//scala:scala.bzl", "scala_binary")

scala_binary(
    name = "app",
    srcs = glob(["src/main/scala/**/*.scala"]),
    main_class = "com.example.Main",
    deps = [
        "@maven//:dev_zio_zio_3",
        "//core",
    ],
    jvm_flags = [
        "-Xmx2g",
        "-XX:+UseG1GC",
    ],
)
```

**Parameters:**
- `main_class` - Fully qualified main class name (required)
- `jvm_flags` - JVM options for runtime
- All `scala_library` parameters apply

**Running:**
```bash
bazel run //path/to:app
```

### scala_test

Used for test suites.

```python
load("@rules_scala//scala:scala.bzl", "scala_test")

scala_test(
    name = "core_test",
    srcs = glob(["src/test/scala/**/*.scala"]),
    deps = [
        "//core",
        "@maven//:dev_zio_zio_test_3",
        "@maven//:dev_zio_zio_test_sbt_3",
    ],
    size = "small",
)
```

**Parameters:**
- `size` - Test size: `small`, `medium`, `large`, `enormous`
- `timeout` - Test timeout override
- All `scala_library` parameters apply

**Running:**
```bash
bazel test //path/to:core_test
```

## Scala 3 Compiler Options

### Common Compiler Flags

```python
scala_library(
    name = "core",
    srcs = [...],
    scalacopts = [
        "-encoding", "utf-8",
        "-feature",
        "-unchecked",
        "-deprecation",
        "-Xfatal-warnings",
        "-language:implicitConversions",
        "-language:higherKinds",
        "-Ykind-projector",
    ],
)
```

**Recommended flags:**
- `-encoding utf-8` - Use UTF-8 source encoding
- `-feature` - Warn about feature usage
- `-unchecked` - Warn about unchecked type patterns
- `-deprecation` - Warn about deprecated APIs
- `-Xfatal-warnings` - Treat warnings as errors
- `-language:implicitConversions` - Enable implicit conversions
- `-language:higherKinds` - Enable higher-kinded types
- `-Ykind-projector` - Enable kind-projector syntax

### Project-Wide Compiler Options

Define in `defs.bzl`:

```python
SCALA_OPTS = [
    "-encoding", "utf-8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-language:implicitConversions",
    "-language:higherKinds",
]

def scala_library_with_defaults(**kwargs):
    scalacopts = kwargs.pop("scalacopts", [])
    scala_library(
        scalacopts = SCALA_OPTS + scalacopts,
        **kwargs
    )
```

## Dependency Management

### Direct Dependencies

```python
scala_library(
    name = "service",
    srcs = ["Service.scala"],
    deps = [
        # Other Scala targets
        "//core:api",
        "//common:utils",

        # Maven dependencies
        "@maven//:dev_zio_zio_3",
        "@maven//:dev_zio_zio_streams_3",
    ],
)
```

### Exported Dependencies

Dependencies that are part of the public API:

```python
scala_library(
    name = "api",
    srcs = ["Api.scala"],
    deps = [
        "@maven//:dev_zio_zio_3",
    ],
    exports = [
        "@maven//:dev_zio_zio_3",  # Consumers get ZIO too
    ],
)

scala_library(
    name = "client",
    srcs = ["Client.scala"],
    deps = [
        "//api",  # Gets ZIO transitively
    ],
)
```

**When to export:**
- Dependency types appear in public API
- Consumers will always need the dependency
- Reduces boilerplate in dependent targets

### Runtime Dependencies

Dependencies only needed at runtime, not compile time:

```python
scala_binary(
    name = "app",
    srcs = ["Main.scala"],
    deps = [
        "@maven//:dev_zio_zio_3",
    ],
    runtime_deps = [
        "@maven//:ch_qos_logback_logback_classic",  # Logging implementation
    ],
)
```

## Scala 3 + ZIO Specific Patterns

### ZIO Macro Dependencies

ZIO macros require compile-time dependencies:

```python
scala_library(
    name = "zio_service",
    srcs = ["Service.scala"],
    deps = [
        # Core ZIO
        "@maven//:dev_zio_zio_3",

        # Required for macro expansion
        "@maven//:dev_zio_izumi_reflect_3",
        "@maven//:dev_zio_izumi_reflect_thirdparty_boopickle_shaded_3",
        "@maven//:dev_zio_zio_stacktracer_3",
        "@maven//:dev_zio_zio_internal_macros_3",
    ],
)
```

**Why needed:**
- ZIO uses macros for `ZLayer`, effect construction, etc.
- Macros execute at compile time and need runtime type information
- `izumi-reflect` provides type information for macros
- Bazel doesn't automatically include macro dependencies

### Custom ZIO Macro

To avoid repetition, create a custom rule:

```python
# defs.bzl
load("@rules_scala//scala:scala.bzl", "scala_library", "scala_binary")

ZIO_DEPS = [
    "@maven//:dev_zio_zio_3",
    "@maven//:dev_zio_izumi_reflect_3",
    "@maven//:dev_zio_izumi_reflect_thirdparty_boopickle_shaded_3",
    "@maven//:dev_zio_zio_stacktracer_3",
    "@maven//:dev_zio_zio_internal_macros_3",
]

def zio_scala_library(name, deps = [], **kwargs):
    scala_library(
        name = name,
        deps = ZIO_DEPS + deps,
        **kwargs
    )

def zio_scala_binary(name, deps = [], **kwargs):
    scala_binary(
        name = name,
        deps = ZIO_DEPS + deps,
        **kwargs
    )
```

**Usage:**
```python
load("//:defs.bzl", "zio_scala_library")

zio_scala_library(
    name = "service",
    srcs = ["Service.scala"],
    deps = [
        # ZIO_DEPS automatically included
        "//core:api",
    ],
)
```

## File Organization

### Glob Patterns

```python
scala_library(
    name = "lib",
    srcs = glob([
        "src/main/scala/**/*.scala",  # All Scala files
        "src/main/java/**/*.java",     # Java files if needed
    ], exclude = [
        "src/main/scala/**/Ignore.scala",  # Exclude specific files
    ]),
)
```

**Best practices:**
- Use `glob()` for large directories
- Be specific to avoid accidental inclusions
- Use `exclude` for exceptions
- List individual files for small sets

### Multiple Source Directories

```python
scala_library(
    name = "lib",
    srcs = glob([
        "src/main/scala/**/*.scala",
        "src/generated/scala/**/*.scala",
    ]),
)
```

## Resources

Include non-code files in JAR:

```python
scala_library(
    name = "lib",
    srcs = glob(["**/*.scala"]),
    resources = glob([
        "src/main/resources/**/*",
    ]),
    resource_strip_prefix = "src/main/resources",
)
```

**Parameters:**
- `resources` - Files to include
- `resource_strip_prefix` - Path prefix to remove from JAR

**Access in code:**
```scala
val stream = getClass.getResourceAsStream("/config.json")
```

## Visibility Control

Control who can depend on your targets:

```python
# Public - anyone can depend
scala_library(
    name = "api",
    visibility = ["//visibility:public"],
)

# Package private - only targets in same package
scala_library(
    name = "internal",
    visibility = ["//visibility:private"],
)

# Custom visibility - specific packages
scala_library(
    name = "core",
    visibility = [
        "//services:__pkg__",
        "//clients:__pkg__",
    ],
)

# Subpackages
scala_library(
    name = "utils",
    visibility = [
        "//services:__subpackages__",  # services and all subpackages
    ],
)
```

## Build Performance

### Incremental Compilation

Bazel automatically handles incremental builds:
- Only recompiles changed files and dependents
- Caches compilation results
- Tracks file-level dependencies

**Tips:**
- Structure code to minimize cross-dependencies
- Keep targets small and focused
- Avoid circular dependencies

### Parallel Compilation

```bash
# Control build parallelism
bazel build //... --jobs=8

# Auto-detect based on CPU cores
bazel build //... --jobs=auto
```

### Caching

```bash
# Local disk cache
bazel build //... --disk_cache=/tmp/bazel_cache

# Remote cache (team builds)
bazel build //... --remote_cache=https://cache.example.com
```

## Testing Configuration

### Test Suites

Group related tests:

```python
test_suite(
    name = "all_tests",
    tests = [
        ":unit_tests",
        ":integration_tests",
    ],
)

scala_test(
    name = "unit_tests",
    srcs = glob(["src/test/scala/unit/**/*.scala"]),
    size = "small",
)

scala_test(
    name = "integration_tests",
    srcs = glob(["src/test/scala/integration/**/*.scala"]),
    size = "medium",
    tags = ["integration"],
)
```

### Test Size and Timeout

```python
scala_test(
    name = "quick_test",
    size = "small",      # Default timeout: 60s
)

scala_test(
    name = "slow_test",
    size = "large",      # Default timeout: 900s
    timeout = "eternal", # Override: unlimited
)
```

**Sizes:**
- `small` - 60s timeout, for unit tests
- `medium` - 300s timeout, for integration tests
- `large` - 900s timeout, for system tests
- `enormous` - 3600s timeout, for very slow tests

### Test Filtering

```bash
# Run specific test
bazel test //path/to:specific_test

# Run all tests in package
bazel test //path/to:all

# Run tests matching tag
bazel test //... --test_tag_filters=unit

# Exclude tests by tag
bazel test //... --test_tag_filters=-integration
```

## Common Patterns

### Multi-Module Project

```
project/
├── MODULE.bazel
├── BUILD.bazel
├── core/
│   └── BUILD.bazel
├── api/
│   └── BUILD.bazel
└── services/
    └── BUILD.bazel
```

```python
# core/BUILD.bazel
scala_library(
    name = "core",
    srcs = glob(["**/*.scala"]),
    visibility = ["//visibility:public"],
)

# api/BUILD.bazel
scala_library(
    name = "api",
    srcs = glob(["**/*.scala"]),
    deps = ["//core"],
    visibility = ["//visibility:public"],
)

# services/BUILD.bazel
scala_binary(
    name = "app",
    srcs = glob(["**/*.scala"]),
    main_class = "com.example.Main",
    deps = [
        "//core",
        "//api",
    ],
)
```

### Shared Dependencies

```python
# defs.bzl
COMMON_DEPS = [
    "@maven//:dev_zio_zio_3",
    "@maven//:org_typelevel_cats_core_3",
]

LUCENE_DEPS = [
    "@maven//:org_apache_lucene_lucene_core",
    "@maven//:org_apache_lucene_lucene_queryparser",
    "@maven//:org_apache_lucene_lucene_analyzers_common",
]

# BUILD.bazel
load("//:defs.bzl", "COMMON_DEPS", "LUCENE_DEPS")

scala_library(
    name = "search",
    srcs = glob(["**/*.scala"]),
    deps = COMMON_DEPS + LUCENE_DEPS,
)
```

## Troubleshooting

### Issue: "Class not found" at compile time

**Cause:** Missing dependency in `deps`

**Solution:**
1. Identify missing class from error
2. Find Maven artifact containing class
3. Add to MODULE.bazel if not present
4. Add to target's `deps`

### Issue: "Conflicting cross-version suffixes"

**Cause:** Mixing Scala 2 and Scala 3 dependencies

**Solution:**
- Ensure all dependencies use `_3` suffix (Scala 3)
- Check transitive dependencies
- Exclude Scala 2 dependencies if needed

### Issue: Macro expansion failures

**Cause:** Missing macro compile-time dependencies

**Solution:**
- Add `ZIO_DEPS` to target
- Or manually add izumi-reflect dependencies
- See "ZIO Macro Dependencies" section above

### Issue: "No main class found"

**Cause:** `main_class` not specified or incorrect

**Solution:**
```python
scala_binary(
    name = "app",
    main_class = "com.example.Main",  # Fully qualified name
)
```

Verify main class exists:
```scala
package com.example

object Main:
  def main(args: Array[String]): Unit = ???
```

## Best Practices

1. **Keep targets small and focused**
   - One library per logical component
   - Easier to cache and parallelize

2. **Use visibility appropriately**
   - Mark internal targets private
   - Only expose public API

3. **Leverage custom macros**
   - Reduce boilerplate
   - Enforce conventions

4. **Group related dependencies**
   - Use constants for common dep sets
   - Document dependency choices

5. **Test incrementally**
   - Build after each significant change
   - Catch errors early

6. **Use glob() wisely**
   - Be specific to avoid surprises
   - Exclude generated or temporary files

## References

- [rules_scala Documentation](https://github.com/bazelbuild/rules_scala)
- [Bazel Scala Tutorial](https://bazel.build/tutorials/scala)
- [Scala 3 Reference](https://docs.scala-lang.org/scala3/reference/)
