# RatesStoreLive Walkthrough

## Overview

## `Ref[F, Snapshot]`: Shared Mutable State Without Locks

`Ref[F, A]` is cats-effect's answer to `AtomicReference<A>` in Java. It holds a single value of
type `A` that can be read and updated concurrently, with all operations running inside `F`.

The key operations are:

- `ref.get` — reads the current value, returns `F[A]`
- `ref.set(a)` — replaces the value, returns `F[Unit]`
- `ref.update(f)` — applies a pure function atomically, like `AtomicReference.updateAndGet`

The difference from `AtomicReference` is that `ref.get` doesn't return an `A` — it returns
`F[A]`. Nothing happens until that effect is executed. This keeps the mutation in the same
effect-description model as everything else, so the compiler can reason about when and whether
it runs.

Here, the `Ref` holds a `Snapshot` — the last successfully fetched batch of rates plus the
timestamp of that fetch. The background polling fiber writes to it via `ref.set`; request
handlers read from it via `ref.get`. No locks, no `synchronized`, no risk of forgetting to
release a monitor.

## The `Snapshot` Data Structure

```scala
final case class Snapshot(entries: Map[Rate.Pair, Rate], refreshedAt: Option[Long])
```

A plain immutable data container — the value stored inside the `Ref`. `entries` is the rate
lookup table keyed by currency pair. `refreshedAt` is the epoch-millisecond timestamp of the
last successful fetch, used by `isFresh` to decide whether the cache has expired.

`Option[Long]` for the timestamp means "no successful fetch yet" maps naturally to `None`,
without needing a sentinel value like `-1`.

## `resource`: Acquire, Warm, and Poll

`RatesStoreLive.resource` is a `Resource[F, RatesStore[F]]`. The acquire step does three things
in order:

1. Creates the `Ref` holding an empty `Snapshot`
2. Calls `store.refresh` immediately — so the cache is warm before any request can arrive
3. Starts a background fiber that polls on a schedule

The release step cancels that fiber when the resource is released (i.e. on shutdown).

The eager initial `refresh` is load-bearing: without it, the first requests would hit an empty
cache and return nothing. The fiber only fires _after_ the first `refreshInterval`, so without
the upfront call there'd be a window of unavailability equal to the entire refresh interval.

## Background Polling with `fs2.Stream`

The polling fiber runs:

```scala
Stream.awakeEvery[F](config.refreshInterval)
  .evalMap(_ => store.refresh.handleErrorWith(_ => Sync[F].unit))
  .compile.drain
```

`Stream.awakeEvery` emits a tick on the given interval — like a `ScheduledExecutorService` that
fires repeatedly, but expressed as a stream. `evalMap` runs an effect for each tick.

The `.handleErrorWith(_ => Sync[F].unit)` swallows errors silently so a single failed refresh
doesn't kill the fiber. The cache goes stale only if _every_ refresh attempt within the TTL
window fails — which `withRetry` in `refresh` already makes unlikely.

`Concurrent[F].start(...)` launches the stream as a background fiber, returning immediately
with a handle that can be cancelled later.

## `refresh` and `withRetry`

`refresh` fetches all currency pairs from One-Frame in a single request, maps the response into
`Rate` values, and atomically replaces the `Snapshot` in the `Ref` with the new data and the
current timestamp.

It wraps `fetchAll` in `withRetry(retries = 2, delay = 30.seconds)`. With a 4-minute TTL and
a 3-minute refresh interval, a single transient failure would otherwise leave the cache stale
for the full next interval — potentially past the TTL. Two retries at 30-second gaps recover
within 1 minute, well inside the TTL window.

`withRetry` is a recursive function: on failure it sleeps, decrements the counter, and calls
itself. When retries hit zero it re-raises the original error, letting the caller decide what
to do (the polling fiber swallows it; the startup call lets it propagate and fail fast).

## `get` and `isFresh`

`get` reads the `Ref` and does a map lookup — two lines, no network call. All the complexity
lives in `refresh`; reads are intentionally trivial.

`isFresh` reads the current time and the `refreshedAt` timestamp from the `Snapshot` and checks
whether the difference is within the configured TTL. `Option.exists` handles the `None` case
(no successful fetch yet) by returning `false`, so a cold cache correctly reports itself as stale.

## Summary

| Concept | Java analogy | What it actually is |
|---|---|---|
| `Ref[F, Snapshot]` | `AtomicReference<Snapshot>` | Concurrency-safe mutable cell; operations return `F[A]` |
| `Snapshot` | Plain DTO | Immutable cache state: rate map + last-refreshed timestamp |
| `resource` acquire | Constructor + `@PostConstruct` | Warms cache eagerly, starts polling fiber |
| `Stream.awakeEvery` | `ScheduledExecutorService` | Periodic tick stream; fiber cancelled on shutdown |
| `withRetry` | Manual retry loop | Recursive, delay-between-attempts, re-raises on exhaustion |
| `get` | Cache lookup | Reads `Ref`, returns `Option[Rate]`; no network |
| `isFresh` | TTL check | Compares current time to `refreshedAt`; `None` → stale |
