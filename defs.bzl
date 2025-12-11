"""Custom Bazel macros for the zio-lucene project."""

load("@rules_scala//scala:scala.bzl", "scala_library", "scala_binary")

# ZIO dependencies that need to be explicitly included for macro expansion
ZIO_DEPS = [
    "@maven//:dev_zio_zio_3",
    "@maven//:dev_zio_izumi_reflect_3",
    "@maven//:dev_zio_izumi_reflect_thirdparty_boopickle_shaded_3",
    "@maven//:dev_zio_zio_stacktracer_3",
    "@maven//:dev_zio_zio_internal_macros_3",
]


def scala_lib(name, deps = [], **kwargs):
    """
    Creates a scala_library without ZIO dependencies (effect-agnostic).
    Source files are automatically globbed from all .scala files in the current directory.

    Args:
        name: The name of the library target
        deps: Dependencies
        **kwargs: Additional arguments to pass to scala_library
    """
    scala_library(
        name = name,
        srcs = native.glob(["*.scala"]),
        deps = deps,
        **kwargs
    )

def zio_scala_library(name, deps = [], **kwargs):
    """
    Creates a scala_library with ZIO dependencies automatically included.
    Source files are automatically globbed from all .scala files in the current directory.

    Args:
        name: The name of the library target
        deps: Additional dependencies beyond ZIO
        **kwargs: Additional arguments to pass to scala_library
    """
    scala_library(
        name = name,
        srcs = native.glob(["*.scala"]),
        deps = ZIO_DEPS + deps,
        **kwargs
    )

def zio_scala_binary(name, main_class, deps = [], **kwargs):
    """
    Creates a scala_binary with ZIO dependencies automatically included.

    Args:
        name: The name of the binary target
        main_class: The main class to run
        deps: Library dependencies
        **kwargs: Additional arguments to pass to scala_binary
    """
    scala_binary(
        name = name,
        main_class = main_class,
        deps = deps,
        **kwargs
    )
