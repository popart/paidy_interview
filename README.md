# pre-requisites
- install scala: https://www.scala-lang.org/download/
  - (this includes sbt)
  - coursier updates path in .profile, manually copy to .zshrc or .bashrc if needed
- pull the one-frame docker image: https://hub.docker.com/r/paidyinc/one-frame

# dependency versions
- sbt 18
- jdk 17

# running the code
```
# run the one-frame server
docker run -p 8080:8080 paidyinc/one-frame

# smoke test hit the one-frame server
curl -H "token: 10dc303535874aeccc86a8251e6992f5" 'localhost:8080/rates?pair=USDJPY'

# run the proxy server
cd forex-mtl
export ONE_FRAME_TOKEN=10dc303535874aeccc86a8251e6992f5
sbt run

# (or for auto-restarts on file saves)
sbt
> ~run

# smoke test hit the proxy server
curl 'http://localhost:8081/rates?from=USD&to=JPY'

```

# running tests
```
cd forex-mtl
sbt test
```

# one-frame API (from the docker page)
GET /rates?pair={currency_pair_0}&pair={currency_pair_1}&...pair={currency_pair_n}

pair: Required query parameter that is the concatenation of two different currency codes, e.g. USDJPY. One or more pairs per request are allowed.

token: Header required for authentication. 10dc303535874aeccc86a8251e6992f5 is the only accepted value in the current implementation.
Example:

request: curl -H "token: 10dc303535874aeccc86a8251e6992f5" 'localhost:8080/rates?pair=USDJPY'

response: [{"from":"USD","to":"JPY","bid":0.61,"ask":0.82,"price":0.71,"time_stamp":"2019-01-01T00:00:00.000"}]

GET /rates should only be used for the Forex take home exercise
