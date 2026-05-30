#!/usr/bin/env python3
"""
Correctness test: POST every entry from test-data.json to localhost:9999/fraud-score
and compare the response to the expected values.
Writes tests/expected.json on success (used by anti_mix.py).
"""
import json, sys, os, urllib.request, urllib.error

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(BASE, "api", "src", "test", "resources", "test-data.json")
OUT  = os.path.join(os.path.dirname(os.path.abspath(__file__)), "expected.json")
URL  = "http://localhost:9999/fraud-score"

def main():
    with open(DATA) as f:
        cases = json.load(f)

    failures = []
    expected_map = {}

    for i, case in enumerate(cases):
        req  = case["request"]
        body = json.dumps(req).encode()
        tx_id = req.get("id", str(i))

        try:
            r = urllib.request.urlopen(
                urllib.request.Request(
                    URL,
                    data=body,
                    headers={"Content-Type": "application/json"},
                    method="POST",
                ),
                timeout=10,
            )
            resp = json.loads(r.read())
        except Exception as e:
            failures.append(f"[{i}] {tx_id}: request error: {e}")
            continue

        exp_approved     = case["expected_approved"]
        exp_fraud_score  = case["expected_fraud_score"]
        got_approved     = resp.get("approved")
        got_fraud_score  = resp.get("fraud_score")

        ok = (got_approved == exp_approved and
              abs(got_fraud_score - exp_fraud_score) < 1e-9)

        expected_map[tx_id] = {
            "request":             req,
            "expected_approved":   exp_approved,
            "expected_fraud_score": exp_fraud_score,
        }

        if not ok:
            failures.append(
                f"[{i}] {tx_id}: "
                f"approved={got_approved} (want {exp_approved}), "
                f"fraud_score={got_fraud_score} (want {exp_fraud_score})"
            )

        if (i + 1) % 100 == 0:
            print(f"  {i+1}/{len(cases)} checked…", flush=True)

    total = len(cases)
    passed = total - len(failures)
    print(f"\n{passed} / {total} OK")

    if failures:
        print("\nFAILURES:")
        for f in failures[:20]:
            print(" ", f)
        if len(failures) > 20:
            print(f"  … and {len(failures)-20} more")
        sys.exit(1)

    with open(OUT, "w") as f:
        json.dump(expected_map, f)
    print(f"Wrote expected map to {OUT}")

if __name__ == "__main__":
    main()
