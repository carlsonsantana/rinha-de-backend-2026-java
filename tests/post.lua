local body = '{"id":"tx-1641912674","transaction":{"amount":441.59,"installments":1,"requested_at":"2027-07-09T16:31:06Z"},"customer":{"avg_amount":883.18,"tx_count_24h":1,"known_merchants":["MERC-004","MERC-017"]},"merchant":{"id":"MERC-004","mcc":"5411","avg_amount":302.78},"terminal":{"is_online":false,"card_present":true,"km_from_home":33.8814492067},"last_transaction":{"timestamp":"2027-06-04T14:14:22Z","km_from_current":18.4353521556}}'

wrk.method = "POST"
wrk.body   = body
wrk.headers["Content-Type"] = "application/json"
