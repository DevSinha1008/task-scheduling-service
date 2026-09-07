"""Load test the scheduler to produce the throughput and latency figures.

    python scripts/loadtest.py http://localhost:8080 5000

Method:
  1. Register a user and obtain a JWT.
  2. Enqueue N jobs concurrently, timing the API's enqueue throughput.
  3. Poll /api/jobs/stats until the queue drains, timing end-to-end
     execution throughput (jobs/sec through the worker pool).
  4. Report p50/p95/p99 of enqueue latency.

Enqueue and drain are reported separately because they measure different
things: enqueue is API + single-row insert, drain is the worker pool
claiming and executing. Conflating them would hide which is the bottleneck.
"""
import sys, time, json, urllib.request, concurrent.futures as cf

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 5000
WORKERS = 16

def req(path, method="GET", body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(f"{BASE}{path}", data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(r, timeout=30) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else {}

def pct(vals, p):
    s = sorted(vals)
    return s[int(p * (len(s) - 1))]

# --- authenticate ---
user = f"loadtest-{int(time.time())}"
token = req("/api/auth/register", "POST",
            {"username": user, "password": "password123"})["token"]
print(f"authenticated as {user}")

# --- enqueue phase ---
latencies = []
def enqueue(i):
    t0 = time.perf_counter()
    req("/api/jobs", "POST", {"name": f"load-job-{i}", "payload": "{}"}, token)
    return (time.perf_counter() - t0) * 1000

t0 = time.perf_counter()
with cf.ThreadPoolExecutor(WORKERS) as ex:
    latencies = list(ex.map(enqueue, range(N)))
enqueue_wall = time.perf_counter() - t0

print(f"\nenqueue throughput : {N/enqueue_wall:8.1f} jobs/sec ({WORKERS} clients)")
print(f"enqueue p50        : {pct(latencies, 0.50):8.1f} ms")
print(f"enqueue p95        : {pct(latencies, 0.95):8.1f} ms")
print(f"enqueue p99        : {pct(latencies, 0.99):8.1f} ms")

# --- drain phase ---
print("\ndraining queue...")
t0 = time.perf_counter()
last_report = 0
while True:
    s = req("/api/jobs/stats", token=token)
    outstanding = s["pending"] + s["running"]
    if outstanding == 0:
        break
    if time.perf_counter() - last_report > 5:
        print(f"  pending={s['pending']} running={s['running']} done={s['succeeded']}")
        last_report = time.perf_counter()
    if time.perf_counter() - t0 > 600:
        print("  timed out after 10 minutes")
        break
    time.sleep(0.25)
drain_wall = time.perf_counter() - t0

final = req("/api/jobs/stats", token=token)
print(f"\nexecution throughput: {final['succeeded']/drain_wall:8.1f} jobs/sec")
print(f"drained in          : {drain_wall:8.1f} s")
print(f"final stats         : {final}")
