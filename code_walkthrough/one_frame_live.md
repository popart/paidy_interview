# OneFrameLive Walkthrough

## Overview

`OneFrameLive` is the service layer sitting between the HTTP routes and the cache. Its only job
is to call `RatesStore` and translate the result into a domain `Either[Error, Rate]` — mapping
`None` to `PairNotFound`, and a stale cache to `StaleRates`.

It has no HTTP knowledge and makes no network calls itself. All that lives in `RatesStoreLive`.

---

## Why `F` Needs to Be a `Monad`

`get` makes two sequential calls to the store — `store.get(pair)` and then, conditionally,
`store.isFresh`. The second call only happens if the first returns `Some`. That sequencing is
expressed with `flatMap`:

```scala
store.get(pair).flatMap {
  case Some(rate) => store.isFresh.map { ... }
  case None       => ...pure[F]
}
```

`flatMap` is the operation that chains two effects where the second depends on the result of
the first. It's the core of `Monad`. Without it you can't write "do this, then based on the
result, do that" inside `F`.

Compare to `Sync` or `Concurrent` — those add threading and side-effect capabilities. `Monad`
is more fundamental: it's just the ability to sequence dependent steps. Since `OneFrameLive`
doesn't sleep, spawn fibers, or make HTTP calls, `Monad` is the minimum constraint that lets
it do its job.

In Java terms: if `F` were `CompletableFuture`, `flatMap` is `thenCompose` — chain a second
async call that depends on the first result. `Monad` is the typeclass that guarantees that
operation exists for whatever `F` is.

---

## `asRight`, `asLeft`, `pure`

These are small convenience methods worth knowing:

- `rate.asRight[Error]` — wraps `rate` in `Right`, with `Error` as the left type. Same as `Right(rate)` but the type is inferred more cleanly.
- `(Error.StaleRates: Error).asLeft[Rate]` — wraps in `Left`, with `Rate` as the right type.
- `.pure[F]` — lifts a plain value into `F` without any effect. `x.pure[F]` is equivalent to `F.pure(x)`, or `CompletableFuture.completedFuture(x)` in Java. Needed here because the `None` branch has no effect to run — it just needs to return an already-known value inside `F` to match the type of the `flatMap`.
