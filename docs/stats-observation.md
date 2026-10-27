# Atomic Web statistics observation

The coordinator is Main-scope confined. A single immutable observation carries
counters, ranking, time, source, error, refreshing, revision and push status.
Overview and Statistics collect it once. Legacy entrypoints are read-only projections.
Room aggregation, rules, certificate operations and wire statistics semantics are unchanged.

## Lifecycle / ordering
- Foreground owns one 10-second poll timer and at most one push collection.
- Per-path mutexes serialize actual IO/socket teardown across cancelled lifecycle sessions.
- Reconnect delays: 1, 2, 4, 8, 16, 30 seconds, then 30 seconds. A valid payload or
  30 seconds of connected monotonic time resets the next delay to one second.
- Connected push never suppresses polling. Background/disabled/closed sessions
  cancel retries; callback tokens reject old sessions.
- HTTP reads capture publication revision. A later accepted push wins against an
  in-flight read/error. Snapshot ordering, uptime/restart and clock rollback use
  the established ordering helpers; no unavailable wire generation is invented.
- Failure retains known values/time and marks stale. Valid empty ranking clears.
  Unknown stays unknown. Poll completion advances a separate log-refresh signal,
  including failed/discarded reads.
- HTTP uses the existing nc/deadline/fallback path; push uses one retained OkHttp
  client and a bounded conflated latest-value event channel.

## Evidence contract
Deterministic tests inject transport, wall/monotonic clocks and timer completion.
The API 30/35 instrumented test uses real nc HTTP and OkHttp WebSocket against an
ephemeral loopback service: abrupt disconnect, reconnect, silent connected polling,
then background cancellation. It does not edit preferences or listener configuration.
Lifecycle tests report max active readers/sockets and pending timers; elapsed time
is an observation, not a claimed throughput/battery improvement.
Android 17 / Root / AdGuard and RISC-V runtime remain separate field acceptance.

The loopback-only push URL is intentionally plain HTTP because that is the daemon
management endpoint contract. A method-local S5332 annotation documents this
reviewed exception, not a project-wide analysis exclusion. Host/scheme are fixed;
the injected port is validated. No remote/upstream TLS validation is relaxed.
