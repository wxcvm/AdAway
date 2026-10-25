"""Paired, bounded loopback load. No result from --quick is migration acceptance."""

import argparse, array, concurrent.futures, json, math, pathlib, statistics, sys, time, platform

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from correctness import Server, response
import psutil

BUCKETS = 1200


def percentile(hist, q):
    total = sum(hist)
    if not total:
        return None
    target = math.ceil(total * q)
    seen = 0
    for i, count in enumerate(hist):
        seen += count
        if seen >= target:
            return math.expm1((i + 1) / 50) / 1000  # ms, <=2.1% bucket width


def measure(server, path, tls, cold, clients, warmup, duration):
    process = psutil.Process(server.process.pid)
    ctx = None
    if tls:
        import ssl

        ctx = ssl.create_default_context(cafile=str(server.root / "ca.pem"))
    start = time.perf_counter() + warmup
    end = start + duration

    def worker(index):
        import socket

        hist = array.array("Q", [0]) * BUCKETS
        count = errors = iteration = 0
        sock = None
        while time.perf_counter() < end:
            before = time.perf_counter()
            try:
                if sock is None:
                    sock = socket.create_connection(
                        ("127.0.0.1", server.https if tls else server.http), timeout=3
                    )
                    if tls:
                        sock = ctx.wrap_socket(
                            sock,
                            server_hostname=(
                                f"cold{index}x{iteration}.spike.invalid"
                                if cold
                                else "warm.spike.invalid"
                            ),
                        )
                meta, _ = response(sock, path, keep=not cold)
                if meta["status"] != (204 if path == "/empty" else 200):
                    raise RuntimeError("wrong response status")
                if before >= start:
                    elapsed = (time.perf_counter() - before) * 1e6
                    hist[min(BUCKETS - 1, int(math.log1p(elapsed) * 50))] += 1
                    count += 1
                if cold:
                    sock.close()
                    sock = None
            except Exception:
                if before >= start:
                    errors += 1
                if sock:
                    sock.close()
                    sock = None
            iteration += 1
        if sock:
            sock.close()
        return count, errors, hist

    with concurrent.futures.ThreadPoolExecutor(max_workers=clients) as pool:
        futures = [pool.submit(worker, i) for i in range(clients)]
        time.sleep(max(0, start - time.perf_counter()))
        cpu_before = sum(process.cpu_times()[:2])
        rss = process.memory_info().rss
        peak = rss
        peak_threads = process.num_threads()
        peak_handles = (
            process.num_handles()
            if hasattr(process, "num_handles")
            else process.num_fds()
        )
        while time.perf_counter() < end:
            peak = max(peak, process.memory_info().rss)
            peak_threads = max(peak_threads, process.num_threads())
            peak_handles = max(
                peak_handles,
                (
                    process.num_handles()
                    if hasattr(process, "num_handles")
                    else process.num_fds()
                ),
            )
            time.sleep(0.05)
        rows = [future.result() for future in futures]
        cpu_after = sum(process.cpu_times()[:2])
        observed_duration = time.perf_counter() - start
    histogram = array.array("Q", [0]) * BUCKETS
    requests = errors = 0
    for count, failed, hist in rows:
        requests += count
        errors += failed
        for i, value in enumerate(hist):
            histogram[i] += value
    return {
        "requests": requests,
        "errors": errors,
        "rps": requests / observed_duration,
        "observed_seconds": observed_duration,
        "p50_ms": percentile(histogram, 0.5),
        "p95_ms": percentile(histogram, 0.95),
        "p99_ms": percentile(histogram, 0.99),
        "cpu_seconds": cpu_after - cpu_before,
        "cpu_per_request_us": (
            (cpu_after - cpu_before) * 1e6 / requests if requests else None
        ),
        "idle_rss": rss,
        "peak_rss": peak,
        "threads": peak_threads,
        "handles_or_fds": peak_handles,
        "histogram": histogram.tolist(),
    }


def summarize(rows, quick):
    groups = {}
    for row in rows:
        key = (row["logging"], row["case"], row["clients"])
        groups.setdefault(key, {}).setdefault(row["backend"], []).append(row)
    expected = {
        (logging, case, clients)
        for logging in (False, True)
        for case in ("empty", "text", "image", "https-keep", "tls-cold")
        for clients in (1, 16, 64)
    }
    comparisons = []
    unstable = False
    regressed = False
    invalid = quick or set(groups) != expected
    for key, backends in groups.items():
        if set(backends) != {"lws", "mongoose"}:
            invalid = True
            continue
        if any(
            len(values) != 5 or {row["round"] for row in values} != set(range(5))
            for values in backends.values()
        ):
            invalid = True
        ratios = {}
        variations = {}
        for metric in ("p95_ms", "cpu_per_request_us", "peak_rss"):
            pairs = []
            for left, right in zip(backends["lws"], backends["mongoose"]):
                a, b = left.get(metric), right.get(metric)
                if a is None or b is None or a <= 0 or b <= 0:
                    invalid = True
                    continue
                pairs.append(a / b)
            if not pairs:
                continue
            median = statistics.median(pairs)
            variation = (max(pairs) - min(pairs)) / median if median else math.inf
            ratios[metric] = median
            variations[metric] = variation
            unstable |= variation > 0.10
            regressed |= median > 1.15 and variation <= 0.10
        comparisons.append(
            {
                "logging": key[0],
                "case": key[1],
                "clients": key[2],
                "median_ratios": ratios,
                "paired_variation": variations,
            }
        )
    invalid |= any(row["errors"] or not row["requests"] for row in rows)
    # Missing prototype capability evidence is never manufactured by throughput data.
    verdict = (
        "INCONCLUSIVE"
        if invalid or unstable
        else "NO-GO" if regressed else "PERFORMANCE-GATE-PASS"
    )
    return {
        "performance_verdict": verdict,
        "migration_verdict": "INCONCLUSIVE",
        "reason": "Full capability matrix must be joined with platform/sanitizer evidence before GO.",
        "comparisons": comparisons,
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--build", required=True)
    p.add_argument("--image", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--quick", action="store_true")
    a = p.parse_args()
    root = pathlib.Path(a.output).resolve()
    root.mkdir(parents=True, exist_ok=True)
    build = pathlib.Path(a.build).resolve()
    suffix = ".exe" if sys.platform == "win32" else ""
    rows = []
    cases = [
        ("empty", "/empty", False, False),
        ("text", "/text", False, False),
        ("image", "/image", False, False),
        ("https-keep", "/text", True, False),
        ("tls-cold", "/empty", True, True),
    ]
    for logging in (False, True):
        for name, path, tls, cold in cases:
            for clients in (1,) if a.quick else (1, 16, 64):
                for round_index in range(1 if a.quick else 5):
                    order = (
                        ("lws", "mongoose")
                        if round_index % 2 == 0
                        else ("mongoose", "lws")
                    )
                    for backend in order:
                        exe = build / ("spike_" + backend + suffix)
                        with Server(
                            exe,
                            pathlib.Path(a.image).resolve(),
                            root,
                            extra=("--logging",) if logging else (),
                        ) as server:
                            metrics = measure(
                                server,
                                path,
                                tls,
                                cold,
                                clients,
                                0.1 if a.quick else 2,
                                0.3 if a.quick else 5,
                            )
                            metrics.update(
                                backend=backend,
                                case=name,
                                logging=logging,
                                clients=clients,
                                round=round_index,
                                binary_bytes=exe.stat().st_size,
                                stats=server.stats(),
                            )
                            rows.append(metrics)
                        (root / "measurements.json").write_text(
                            json.dumps(
                                {
                                    "quick": a.quick,
                                    "platform": platform.platform(),
                                    "rows": rows,
                                },
                                indent=2,
                            )
                            + "\n",
                            encoding="utf8",
                        )
                        print(
                            json.dumps(
                                {
                                    k: v
                                    for k, v in metrics.items()
                                    if k not in ("histogram", "stats")
                                }
                            ),
                            flush=True,
                        )
    result = summarize(rows, a.quick)
    (root / "comparison.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf8"
    )
    print(json.dumps({"performance_verdict": result["performance_verdict"]}))
