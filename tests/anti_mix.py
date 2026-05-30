#!/usr/bin/env python3
"""
Anti-mixing test: 10 rounds × 256 concurrent requests.
Each request carries a known input; the driver verifies the response matches
what correctness.py pre-computed for that exact input.
Any mismatch means the LB cross-wired a response to the wrong client.
"""
import asyncio, json, os, random, sys

try:
    import aiohttp
except ImportError:
    print("aiohttp not installed — run: pip install aiohttp")
    sys.exit(1)

BASE     = os.path.dirname(os.path.abspath(__file__))
EXP_FILE = os.path.join(BASE, "expected.json")
URL      = "http://localhost:9999/fraud-score"
ROUNDS   = 10
CONCURRENCY = 256

def load_expected():
    if not os.path.exists(EXP_FILE):
        print(f"ERROR: {EXP_FILE} not found — run correctness.py first")
        sys.exit(1)
    with open(EXP_FILE) as f:
        return json.load(f)

async def do_request(session, tx_id, entry, results):
    body = json.dumps(entry["request"]).encode()
    try:
        async with session.post(
            URL,
            data=body,
            headers={"Content-Type": "application/json"},
            timeout=aiohttp.ClientTimeout(total=30),
        ) as r:
            resp = await r.json()
    except Exception as e:
        results.append((tx_id, f"request error: {e}", None, None))
        return

    got_approved    = resp.get("approved")
    got_fraud_score = resp.get("fraud_score")
    exp_approved    = entry["expected_approved"]
    exp_fraud_score = entry["expected_fraud_score"]

    ok = (got_approved == exp_approved and
          abs(got_fraud_score - exp_fraud_score) < 1e-9)
    if not ok:
        results.append((
            tx_id,
            f"approved={got_approved} (want {exp_approved}), "
            f"fraud_score={got_fraud_score} (want {exp_fraud_score})",
            entry["request"],
            resp,
        ))

async def run_round(expected, rnd, connector):
    keys = list(expected.keys())
    chosen = [random.choice(keys) for _ in range(CONCURRENCY)]
    results = []
    async with aiohttp.ClientSession(connector=connector,
                                     connector_owner=False) as session:
        tasks = [do_request(session, k, expected[k], results) for k in chosen]
        await asyncio.gather(*tasks)
    return results

async def main():
    expected = load_expected()
    total = 0
    all_failures = []

    connector = aiohttp.TCPConnector(limit=CONCURRENCY + 32)
    for rnd in range(1, ROUNDS + 1):
        failures = await run_round(expected, rnd, connector)
        total   += CONCURRENCY
        passed   = CONCURRENCY - len(failures)
        print(f"Round {rnd}/{ROUNDS}: {passed}/{CONCURRENCY} OK", flush=True)
        all_failures.extend(failures)

    await connector.close()

    print(f"\n{total - len(all_failures)} / {total} OK")
    if all_failures:
        print("\nMISMATCHES (first 10):")
        for tx_id, msg, req, resp in all_failures[:10]:
            print(f"  tx_id={tx_id}: {msg}")
            if req:
                print(f"    request:  {json.dumps(req)}")
            if resp:
                print(f"    response: {json.dumps(resp)}")
        sys.exit(1)

if __name__ == "__main__":
    asyncio.run(main())
