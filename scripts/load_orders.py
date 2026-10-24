"""Opt-in order admission load test. Creates real reservations: use a dedicated test activity.
Token file: JSON array of distinct test-user login tokens, kept outside Git (e.g. tmp/tokens.json).
Admission RPS is NOT committed-order throughput. Reconcile DB inventory and queues separately.
"""
import argparse
import concurrent.futures
import json
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--tokens-file', required=True)
parser.add_argument('--voucher-id', required=True, type=int)
parser.add_argument('--url', default='http://127.0.0.1:8080/api')
parser.add_argument('--requests', type=int, default=100)
parser.add_argument('--concurrency', type=int, default=20)
args = parser.parse_args()
with open(args.tokens_file, encoding='utf-8-sig') as file:
    tokens = json.load(file)
if not isinstance(tokens, list) or not tokens or not all(isinstance(t, str) and t for t in tokens):
    raise SystemExit('Expected a nonempty JSON array of login tokens')
if args.requests < 1 or not 1 <= args.concurrency <= 500:
    raise SystemExit('Invalid requests/concurrency')
def request(index):
    start = time.perf_counter()
    try:
        req = urllib.request.Request(args.url.rstrip('/') + '/voucher-order/seckill/' + str(args.voucher_id),
                                     b'', {'authorization': tokens[index % len(tokens)]}, method='POST')
        with urllib.request.urlopen(req, timeout=15) as response:
            payload = json.load(response)
        outcome = 'accepted' if payload.get('success') else 'business_rejected'
    except Exception:
        outcome = 'transport_failed'
    return outcome, (time.perf_counter() - start) * 1000
start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(args.concurrency) as pool:
    results = list(pool.map(request, range(args.requests)))
elapsed = time.perf_counter() - start
durations = sorted(ms for _, ms in results)
counts = {key: sum(outcome == key for outcome, _ in results)
          for key in ('accepted', 'business_rejected', 'transport_failed')}
print(json.dumps({**counts, 'distinct_tokens': len(set(tokens)), 'seconds': elapsed,
                  'request_rps': len(results) / elapsed, 'accepted_rps': counts['accepted'] / elapsed,
                  'p95_ms': durations[min(len(durations) - 1, int(len(durations) * .95))],
                  'p99_ms': durations[min(len(durations) - 1, int(len(durations) * .99))],
                  'note': 'Measure committed DB orders and inventory separately; repeated-user rejections do not prove order throughput.'}, indent=2))
