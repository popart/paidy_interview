# Main.scala Walkthrough

## Overview

`Main.scala` is the application entry point. It wires together the HTTP client, configuration,
and HTTP server into a single running program. The file is short — about 30 lines — but it
introduces several concepts that look nothing like a Java `main` method.

The full file:

```scala
object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)

}

class Application[F[_]: ConcurrentEffect: Timer] {

  def stream(ec: ExecutionContext): Stream[F, Unit] =
    for {
      config <- Config.stream("app")
      client <- Stream.resource(BlazeClientBuilder[F](ec).resource)
      module <- Stream.resource(Module.resource[F](config, client))
      _ <- BlazeServerBuilder[F](ec)
            .bindHttp(config.http.port, config.http.host)
            .withHttpApp(module.httpApp)
            .serve
    } yield ()

}
```

---

## `IOApp`: The Entry Point

In Java, you write:

```java
public static void main(String[] args) { ... }
```

The JVM calls `main`, you do your work, and you return. Side effects happen right there in the method body.

In this codebase, `Main` extends `IOApp` instead. `IOApp` is a trait from the cats-effect library that plays the same role as `public static void main`, but with one critical difference: **you don't execute side effects directly. You describe them and return that description.**

`IOApp` handles the actual execution — it calls your `run` method, takes the `IO[ExitCode]` you return, and runs it on the JVM. You never call `.run()` or `.execute()` yourself (except in tests).

This separation between _describing_ an effect and _running_ it is the central idea in cats-effect, and it will come up in every file in this codebase.

---

## `IO[ExitCode]`: Effects as Values

`run` returns `IO[ExitCode]`, not `ExitCode`.

`IO[A]` is a value that _describes_ a computation that will eventually produce an `A`. Think of it like a `Callable<A>` in Java — it's a recipe, not a result. Handing someone an `IO[ExitCode]` doesn't run anything. It just hands them the recipe.

This lets you compose effects the same way you compose data. You can pass them around, chain them, wrap them in retries, race them against timeouts, all before a single side effect happens.

`IO` specifically means: "run on the cats-effect runtime, using real JVM threads." Later you'll see `F[_]` used instead of `IO` directly — that's the same idea made more general. `IO` is the concrete implementation you wire in at the edge (here, in `Main`).

---

## `Application[F[_]]`: Abstracting Over the Effect Type

```scala
class Application[F[_]: ConcurrentEffect: Timer]
```

The `[F[_]]` says: "`Application` is parameterized by a type constructor `F` that takes one type argument." In Java terms, this is roughly a `class Application<F>` where `F` is not a plain type like `String`, but a _container type_ like `List`, `Optional`, or `CompletableFuture`.

The concrete `F` used here is `IO`, wired in at the `Main` call site:

```scala
new Application[IO].stream(executionContext)
```

**Why not just use `IO` directly?** Testability and flexibility. Code written against `F[_]` can be tested with a different effect type that's easier to control, and it makes the constraints explicit and visible. `Application` doesn't care what `F` is as long as it supports the operations it needs — those requirements are listed as the type constraints.

---

## Type Constraints: `ConcurrentEffect` and `Timer`

```scala
class Application[F[_]: ConcurrentEffect: Timer]
```

The `: ConcurrentEffect: Timer` part is Scala's context bound syntax. It's shorthand for requiring implicit values:

```scala
class Application[F[_]](implicit ce: ConcurrentEffect[F], timer: Timer[F])
```

This reads: "whoever constructs an `Application[F]` must supply evidence that `F` supports concurrent effects and time-based operations."

- **`ConcurrentEffect[F]`** — `F` can run fibers (lightweight threads), race effects, and interop with the outside world (start/cancel async work). It's a superset of `Sync[F]` (synchronous effects) and `Async[F]` (callbacks → `F` bridge).
- **`Timer[F]`** — `F` can sleep and measure time. Used here because building a server that polls on a schedule requires `sleep`.

When `F = IO`, the compiler automatically provides both of these — `IO` satisfies all of cats-effect's typeclasses out of the box. You don't write anything extra; the implicit is already in scope.

---

## `Stream[F, Unit]`: Composing the Application as a Stream

`stream` returns `Stream[F, Unit]` from the fs2 library.

`Stream[F, A]` is a potentially infinite, effectful sequence of `A` values, where each step runs in `F`. Here, `A = Unit` — we don't care about the emitted values, only the side effects that happen while running (binding to a port, accepting connections, etc.).

Using a `Stream` to represent the whole application might seem odd at first. The reason is lifecycle: servers, clients, and connection pools all have _acquire_ and _release_ phases. `Stream` composes those naturally. When the stream ends (or is interrupted), each resource is released in reverse acquisition order — like a stack of `try/finally` blocks, but composable and guaranteed even under exceptions.

In Java you'd manage this with `AutoCloseable`, `try-with-resources`, or a DI framework's lifecycle hooks. `Stream` handles it structurally.

---

## `for` Comprehension Over Streams

```scala
for {
  config <- Config.stream("app")
  client <- Stream.resource(BlazeClientBuilder[F](ec).resource)
  module <- Stream.resource(Module.resource[F](config, client))
  _ <- BlazeServerBuilder[F](ec)
        .bindHttp(config.http.port, config.http.host)
        .withHttpApp(module.httpApp)
        .serve
} yield ()
```

Scala's `for` comprehension is syntactic sugar for chained `flatMap` calls — the same as Java's stream pipeline `.flatMap(...).flatMap(...)`, but readable as sequential steps.

Each `<-` line means: "run this, bind the result to this name, then continue." The steps execute in order, and each step can use the results of previous ones (`module` uses `config` and `client`).

Line by line:

- `Config.stream("app")` — loads `application.conf` from the classpath and emits a single `ApplicationConfig`. The `stream` shape lets it integrate cleanly with the rest.
- `Stream.resource(BlazeClientBuilder[F](ec).resource)` — acquires an HTTP client backed by a thread pool. `Stream.resource` lifts a `Resource[F, A]` into a stream that emits one value and releases the resource when the stream ends.
- `Stream.resource(Module.resource[F](config, client))` — builds the application module (routes, services, caches) as a resource. This is where `RatesStoreLive` gets created and the background polling fiber gets started.
- `BlazeServerBuilder[F](ec)...serve` — binds to the configured host/port and starts serving. This stream element runs forever (until the process is killed), which keeps the whole `for` block running.

---

## Compiling and Draining the Stream

```scala
new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)
```

`Stream` is lazy — defining it does nothing. To actually run it, you _compile_ it down to an `F` value.

- `.compile` — enters the compile phase, making stream-to-effect conversion methods available.
- `.drain` — runs the stream purely for its effects, discarding all emitted values. Returns `F[Unit]`.
- `.as(ExitCode.Success)` — maps the `F[Unit]` to `F[ExitCode]`, replacing the `Unit` with `ExitCode.Success`. This is what `IOApp.run` requires.

After `.compile.drain`, there's no stream anymore — just an `IO[Unit]` that, when executed by `IOApp`, will start the server and block until the process exits.

---

## Summary

| Concept | Java analogy | What it actually is |
|---|---|---|
| `IOApp` | `public static void main` | Trait that executes your `IO[ExitCode]` |
| `IO[A]` | `Callable<A>` | A description of an effectful computation |
| `F[_]` | `<F>` (generic container) | Abstract effect type; `IO` is the concrete implementation |
| `ConcurrentEffect[F]` | — | Typeclass evidence that `F` supports concurrency |
| `Timer[F]` | `ScheduledExecutorService` | Typeclass evidence that `F` can sleep/measure time |
| `Stream[F, Unit]` | `AutoCloseable` pipeline | Composable sequence of effects with managed lifecycles |
| `.compile.drain` | `.forEach(x -> {})` + run | Converts a stream into a single `F` that runs it to completion |

The key takeaway: **nothing in this file actually executes**. `Main.scala` builds a description of the entire application — resources, server, polling fibers — and hands it to `IOApp`, which runs it. Every other file in this codebase follows the same pattern.
