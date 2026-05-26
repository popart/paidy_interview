# Module.scala Walkthrough

## Overview

`Module.scala` is the dependency wiring layer — the equivalent of a Spring `@Configuration` class
or a Guice module. It takes the raw config and HTTP client, builds all the application components
in the correct order, and exposes a single `httpApp` that the server in `Main.scala` can serve.

The full file:

```scala
class Module[F[_]: Concurrent: Timer] private (
    config: ApplicationConfig,
    store: RatesStore[F]
) {
  private val ratesService: RatesService[F] = RatesServices.live(store)
  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = { http: HttpRoutes[F] =>
    AutoSlash(http)
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)
}

object Module {
  def resource[F[_]: Concurrent: Timer](config: ApplicationConfig, client: Client[F]): Resource[F, Module[F]] =
    RatesStoreLive.resource[F](client, config.oneFrame).map(new Module[F](config, _))
}
```

---

## `Module` as a Dependency Injection Container

In Java/Spring you'd annotate a class with `@Configuration` and define `@Bean` methods. The
framework scans, wires, and manages lifecycle. Here there's no framework — just a class whose
constructor takes the dependencies it needs and builds everything else from them.

```scala
class Module[F[_]: Concurrent: Timer] private (
    config: ApplicationConfig,
    store: RatesStore[F]
)
```

`config` and `store` are the two external inputs. Everything else — service, program, routes,
middleware — is derived from them inside the class body as `private val`s. The only public surface
is `httpApp`, the assembled `HttpApp[F]` that the server mounts.

The `private` constructor means nothing outside this file can call `new Module(...)` directly.
Construction is forced through `Module.resource`, which ensures the `RatesStore` is properly
initialized (cache warmed, background fiber running) before the module is handed to the caller.
This is the same guarantee you'd get from a factory method, but enforced by the compiler rather
than documentation.

---

## `Resource[F, Module[F]]`: Lifecycle-Managed Construction

```scala
object Module {
  def resource[F[_]: Concurrent: Timer](config: ApplicationConfig, client: Client[F]): Resource[F, Module[F]] =
    RatesStoreLive.resource[F](client, config.oneFrame).map(new Module[F](config, _))
}
```

`Resource[F, A]` represents a value of type `A` that has an acquire step and a release step.
Think of it as a typed, composable `try/finally`:

```java
// Java equivalent (roughly)
RatesStore store = acquireStore(client, config);  // acquire
try {
    return new Module(config, store);
} finally {
    store.shutdown();  // release — guaranteed even on exception
}
```

`RatesStoreLive.resource` returns a `Resource[F, RatesStore[F]]` — acquiring it starts the
background polling fiber, releasing it cancels that fiber. `.map(new Module[F](config, _))`
transforms the `Resource` so that what the caller receives is a fully-built `Module[F]`, but the
underlying resource (the store and its fiber) is still what gets released on shutdown.

The caller in `Main.scala` uses `Stream.resource(Module.resource(...))`, which ties the module's
lifetime to the stream's lifetime. When the server stops, the fiber is cancelled automatically.

---

## Wiring the Layers: Store → Service → Program → Routes

The application has four layers stacked on top of each other:

```
RatesStore[F]       — raw data access (fetches from One-Frame, owns the cache)
    ↓
RatesService[F]     — thin interface over the store; error type mapped to domain errors
    ↓
RatesProgram[F]     — business logic; validates inputs, calls the service
    ↓
RatesHttpRoutes[F]  — HTTP decoding/encoding; calls the program
```

Each layer only knows about the one below it. `RatesHttpRoutes` has no idea a cache exists.
`RatesProgram` has no idea what HTTP looks like. This separation means each layer can be tested
independently — you can unit test `RatesProgram` by passing a fake `RatesService`, without
spinning up an HTTP server or a real cache.

In `Module`, the wiring is three lines:

```scala
private val ratesService: RatesService[F] = RatesServices.live(store)
private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)
private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes
```

`store` comes in via the constructor; everything else flows downward from it.

---

## Summary

| Concept | Java analogy | What it actually is |
|---|---|---|
| `Module` class | `@Configuration` class | Wires all dependencies; exposes `httpApp` |
| `private` constructor | factory-only bean | Forces construction through `Module.resource` |
| `Resource[F, Module[F]]` | `try/finally` + factory | Acquire (warm cache, start fiber) + guaranteed release |
| Four-layer stack | Service/Repository pattern | Each layer isolated from the others; testable independently |
| `httpApp` | `DispatcherServlet` / router | The assembled, middleware-wrapped HTTP handler |
