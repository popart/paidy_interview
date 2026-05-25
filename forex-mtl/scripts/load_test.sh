#!/usr/bin/env bash
# The "shebang" above tells the OS to run this file with bash. `env bash` finds
# bash via the user's PATH so it works on systems where bash isn't at /bin/bash.
#
# What this script does:
#   Fires N HTTP requests at the local forex proxy in parallel and prints
#   each response (pair, JSON body, HTTP status) to the terminal.
#
# Usage:
#   ./scripts/load_test.sh              # defaults: 1200 requests, 50 in parallel
#   ./scripts/load_test.sh 5000 100     # 5000 requests, 100 in parallel

# `set` configures shell behavior for the rest of the script:
#   -e  exit immediately if any command fails (don't keep going on errors)
#   -u  treat use of unset variables as an error
#   -o pipefail  if any command in a pipeline fails, the whole pipeline fails
set -euo pipefail

# Read positional arguments $1 and $2. The `${VAR:-default}` syntax means
# "use $VAR if it's set, otherwise use 'default'".
N="${1:-1200}"           # total number of requests to send
CONCURRENCY="${2:-50}"   # how many requests to run in parallel

echo "Sending $N requests with $CONCURRENCY workers..."

# This pipeline has two stages connected by `|`:
#
#   seq 1 "$N"
#     Prints the numbers 1..N, one per line. We don't care about the values —
#     we just want N lines so xargs runs N worker invocations.
#
#   xargs -n1 -P"$CONCURRENCY" -I{} bash -c '...'
#     For each line of input, run the `bash -c '...'` command.
#       -n1                one input line per invocation
#       -P<n>              run up to <n> invocations in parallel
#       -I{}               placeholder for the input value (unused here)
#
# The single-quoted string after `bash -c` is the worker script that each
# parallel curl invocation runs. Single quotes prevent the OUTER shell from
# expanding variables — `$from`, `$to`, `$RANDOM` are expanded by the INNER
# bash, not by this script. That's important: each worker picks its own
# random pair independently.
seq 1 "$N" | xargs -n1 -P"$CONCURRENCY" -I{} bash -c '
  # Bash array of "from to" pairs. Parentheses build the array; each
  # quoted string is one element.
  pairs=("USD JPY" "USD EUR" "GBP USD" "EUR JPY")

  # $RANDOM is a built-in bash variable that returns a fresh random integer.
  # `% ${#pairs[@]}` modulo the array length.
  #
  # `read -r from to <<< "..."` splits the string by whitespace and assigns
  # the first word to `from`, the second to `to`. `<<<` is a "here-string":
  # it feeds the string in as stdin to `read`.
  read -r from to <<< "${pairs[$(( RANDOM % ${#pairs[@]} ))]}"

  # curl flags:
  #   -s                 silent (no progress bar)
  #   -w "\n%{http_code}"  after the body, write a newline and the status
  # `$(...)` is command substitution: it runs the command and captures stdout.
  body=$(curl -s -w "\n%{http_code}" "http://localhost:8081/rates?from=${from}&to=${to}")

  # `${var//pattern/replacement}` replaces ALL occurrences of pattern in var.
  # Here we flatten the embedded newline so the body and status appear on one
  # line with the pair, e.g.:  USD->JPY {"from":"USD",...} 200
  echo "${from}->${to} ${body//$'\''\n'\''/ }"
'
