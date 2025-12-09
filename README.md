# ZIO Lucene Search Engine

A simple search engine built with Apache Lucene and ZIO in Scala 3, using Bazel as the build system.

## Prerequisites

- Bazel 7.0 or later
- Java 11 or later

## Project Structure

```
zio-lucene/
├── MODULE.bazel          # Bazel module configuration
├── BUILD.bazel           # Build targets
├── .bazelrc             # Bazel configuration
└── src/
    └── main/
        └── scala/
            └── com/
                └── example/
                    └── search/
                        ├── SearchEngine.scala  # Core search engine implementation
                        └── Main.scala          # Example usage
```

## Building

```bash
bazel build //:search-engine
```

## Running

```bash
bazel run //:search-engine
```

## Features

- **In-memory indexing** using Lucene's ByteBuffersDirectory
- **Full-text search** with StandardAnalyzer
- **ZIO integration** for effect management
- **Resource safety** with ZIO's Scope and automatic cleanup

## Example Output

The example application indexes three documents and performs searches:
- Search for "programming" finds documents about Scala and ZIO
- Search for "library" finds documents about ZIO and Lucene

## Extending

To add more functionality:
1. Add methods to the `SearchEngine` trait
2. Implement them in the `SearchEngine.make()` factory
3. Use them in your application via ZIO's dependency injection
