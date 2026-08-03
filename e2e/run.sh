#!/usr/bin/env bash
# End-to-end test for mcp-lucene-server: drives the packaged fat JAR over the real
# JSON-RPC/stdio protocol (not through JUnit), against a disposable fixture project.
#
# Usage: e2e/run.sh [--build]
#   --build   run `mvn -q package` first instead of reusing the existing jar.
#
# Responses are read strictly in send order (the server processes stdin lines
# synchronously, one response per request): call `advance_response_line` as a
# plain statement, then fetch it via `fetch_raw "$RESPONSE_LINE"`/`fetch_text "$RESPONSE_LINE"`.
# Bash note: any helper that mutates a global counter must be called as a plain
# statement, never through `x=$(helper)` — command substitution forks a subshell,
# so the mutation would be silently lost on return. This is why advancing and
# fetching are two separate steps instead of one convenience function.

set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$PROJECT_ROOT/target/mcp-lucene-server-1.0-SNAPSHOT.jar"
RESPONSE_TIMEOUT_SECS=10

PASS_COUNT=0
FAIL_COUNT=0

if [[ "${1:-}" == "--build" || ! -f "$JAR" ]]; then
    echo "Building jar..."
    (cd "$PROJECT_ROOT" && mvn -q package -DskipTests) || { echo "Build failed"; exit 1; }
fi

FIXTURE_DIR="$(mktemp -d)"
WORK_DIR="$(mktemp -d)"
FIFO="$WORK_DIR/server.in"
OUT_FILE="$WORK_DIR/server.out"
ERR_FILE="$WORK_DIR/server.err"
mkfifo "$FIFO"
touch "$OUT_FILE"

SERVER_PID=""

cleanup() {
    # Plain `exec 3>&-` would error loudly (and redirecting its stderr via a bare
    # `exec ... 2>/dev/null` would permanently silence this shell's stderr, not just
    # this command) if fd 3 was never opened, so only close it when it actually is.
    [[ -e "/proc/$$/fd/3" ]] && exec 3>&-
    if [[ -n "$SERVER_PID" ]]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$FIXTURE_DIR" "$WORK_DIR"
}
trap cleanup EXIT

setup_fixture() {
    git -C "$FIXTURE_DIR" init -q
    mkdir -p "$FIXTURE_DIR/src" "$FIXTURE_DIR/ignored"
    printf 'ignored/\n' > "$FIXTURE_DIR/.gitignore"
    printf 'class UserService {\n    fun login() {}\n}\n\n// UserService mentioned only in a comment here, not a real reference\nfun caller() {\n    val s = UserService()\n    s.login()\n}\n' > "$FIXTURE_DIR/src/App.kt"
    printf 'package pkg.other\n\nfun other(login: Int) {\n    println(login)\n}\n' > "$FIXTURE_DIR/src/Unrelated.kt"
    printf 'package pkg.other\n\nimport UserService.login\n\nfun run2() {\n    login()\n}\n' > "$FIXTURE_DIR/src/Caller.kt"
    printf 'TODO: refactor UserService\n' > "$FIXTURE_DIR/README.md"
    printf 'should never be indexed or grepped\n' > "$FIXTURE_DIR/ignored/vendor.js"
}

start_server() {
    java -jar "$JAR" "$FIXTURE_DIR" < "$FIFO" > "$OUT_FILE" 2> "$ERR_FILE" &
    SERVER_PID=$!
    exec 3>"$FIFO"
    # Give the server time to open the FSDirectory-backed index and complete the initial sync.
    for _ in $(seq 1 50); do
        grep -q "listening on stdin" "$ERR_FILE" 2>/dev/null && return 0
        sleep 0.2
    done
    echo "Server did not start in time. stderr:"
    cat "$ERR_FILE"
    exit 1
}

REQUEST_ID=0
RESPONSE_LINE=0

# Plain statement only — mutates REQUEST_ID, must never be called as `x=$(send ...)`.
send() {
    local method="$1" params="$2"
    REQUEST_ID=$((REQUEST_ID + 1))
    local req
    req=$(jq -nc --arg method "$method" --argjson params "$params" --argjson id "$REQUEST_ID" \
        '{jsonrpc:"2.0", id:$id, method:$method, params:$params}')
    echo "$req" >&3
}

# Plain statement only — same reason as send().
call_tool() {
    local name="$1" arguments="$2"
    send "tools/call" "$(jq -nc --arg name "$name" --argjson arguments "$arguments" '{name:$name, arguments:$arguments}')"
}

# Pure read of a specific (already-known) line number; safe to call via $(...).
read_response_line() {
    local target_line="$1"
    local waited=0
    while (( waited < RESPONSE_TIMEOUT_SECS * 10 )); do
        if (( $(wc -l < "$OUT_FILE") >= target_line )); then
            sed -n "${target_line}p" "$OUT_FILE"
            return 0
        fi
        sleep 0.1
        waited=$((waited + 1))
    done
    echo "TIMEOUT waiting for response #$target_line" >&2
    return 1
}

# Plain statement only — mutates RESPONSE_LINE, must never be called as `x=$(advance...)`.
advance_response_line() {
    RESPONSE_LINE=$((RESPONSE_LINE + 1))
}

# Pure: fetches the raw response line at the given (already-advanced) number. Safe via $(...).
fetch_raw() {
    read_response_line "$1"
}

# Pure: fetches the tool result text (or error message) for the given response line number.
fetch_text() {
    read_response_line "$1" | jq -r '.result.content[0].text // (.error.message // "")'
}

assert_contains() {
    local description="$1" haystack="$2" needle="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        echo "  PASS: $description"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "  FAIL: $description"
        echo "        expected to find: $needle"
        echo "        in: $(echo "$haystack" | head -c 300)"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
}

assert_not_contains() {
    local description="$1" haystack="$2" needle="$3"
    if [[ "$haystack" != *"$needle"* ]]; then
        echo "  PASS: $description"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "  FAIL: $description (unexpectedly found: $needle)"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
}

echo "=== Setting up fixture at $FIXTURE_DIR ==="
setup_fixture

echo "=== Starting server ==="
start_server

echo "=== initialize ==="
send "initialize" '{}'
advance_response_line
line=$(fetch_raw "$RESPONSE_LINE")
assert_contains "initialize returns server name" "$line" "mcp-lucene-server"

echo "=== tools/list ==="
send "tools/list" '{}'
advance_response_line
line=$(fetch_raw "$RESPONSE_LINE")
tool_names=$(echo "$line" | jq -r '.result.tools[].name' | sort | tr '\n' ' ')
assert_contains "tools/list exposes all 7 tools" "$tool_names" "find_definition"
assert_contains "tools/list exposes all 7 tools" "$tool_names" "find_references"
assert_contains "tools/list exposes all 7 tools" "$tool_names" "grep_code"
assert_contains "tools/list exposes all 7 tools" "$tool_names" "list_files"
assert_contains "tools/list exposes all 7 tools" "$tool_names" "read_file"
assert_contains "tools/list exposes all 7 tools" "$tool_names" "reindex_code"
assert_contains "tools/list exposes all 7 tools" "$tool_names" "search_code"

echo "=== search_code ==="
call_tool "search_code" '{"query":"content:UserService","limit":5}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "search_code finds UserService" "$text" "UserService"
assert_contains "search_code reports hit count" "$text" "result(s)"

echo "=== search_code respects gitignore (never indexed ignored/) ==="
call_tool "search_code" '{"query":"content:vendor","limit":5}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "search_code finds nothing in gitignored dir" "$text" "0 result(s)"

echo "=== grep_code: literal + line number ==="
call_tool "grep_code" '{"pattern":"fun login","literal":true}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "grep_code reports file:line" "$text" "src/App.kt:2"

echo "=== grep_code: case-insensitive ==="
call_tool "grep_code" '{"pattern":"userservice","literal":true,"caseSensitive":false}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "grep_code case-insensitive matches App.kt" "$text" "App.kt"
assert_contains "grep_code case-insensitive matches README.md" "$text" "README.md"

echo "=== grep_code: respects gitignore ==="
call_tool "grep_code" '{"pattern":"vendor","literal":true}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "grep_code finds nothing in gitignored dir" "$text" "No matches found"

echo "=== grep_code: files_with_matches mode ==="
call_tool "grep_code" '{"pattern":"UserService","literal":true,"outputMode":"files_with_matches"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "files_with_matches lists App.kt" "$text" "src/App.kt"
assert_contains "files_with_matches lists README.md" "$text" "README.md"

echo "=== grep_code: invalid regex is a clean error, not a crash ==="
call_tool "grep_code" '{"pattern":"(unclosed"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "invalid regex reports a parse error" "$text" "Invalid regex pattern"

echo "=== read_file: line range ==="
call_tool "read_file" '{"path":"src/App.kt","startLine":2,"endLine":2}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "read_file returns requested line" "$text" "2: "
assert_not_contains "read_file excludes line 1" "$text" "1: class UserService"

echo "=== read_file: path traversal is rejected ==="
call_tool "read_file" '{"path":"../outside.txt"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "read_file blocks escaping the project root" "$text" "escapes the project directory"

echo "=== list_files: glob filter ==="
call_tool "list_files" '{"pattern":"**/*.kt"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "list_files finds App.kt" "$text" "src/App.kt"
assert_not_contains "list_files excludes README.md for *.kt filter" "$text" "README.md"

echo "=== find_definition: finds the class definition, not a mention ==="
call_tool "find_definition" '{"symbol":"UserService"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "find_definition locates the class" "$text" "src/App.kt:1"
assert_contains "find_definition tags the kind" "$text" "[class]"
assert_not_contains "find_definition skips the README mention" "$text" "README.md"
assert_contains "find_definition is not fooled by the comment mention (AST, not regex/text)" "$text" "Found 1 definition"

echo "=== find_definition: unknown symbol reports no definition ==="
call_tool "find_definition" '{"symbol":"TotallyUnknownSymbolZZZ"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "find_definition reports absence cleanly" "$text" "No definition found"

echo "=== find_references: finds the definition and the real call site, not the comment ==="
call_tool "find_references" '{"symbol":"UserService"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "find_references reports definition + call + the Caller.kt import (not the comment mention)" "$text" "Found 3 reference(s)"
assert_contains "find_references tags the declaration" "$text" "src/App.kt:1 [definition]"
assert_contains "find_references tags the constructor call" "$text" "src/App.kt:7 [call]"
assert_contains "find_references tags the Caller.kt import" "$text" "src/Caller.kt:3 [import]"
assert_not_contains "find_references skips the README mention" "$text" "README.md"

echo "=== find_references: finds a method call site ==="
call_tool "find_references" '{"symbol":"login"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "find_references tags the method declaration" "$text" "src/App.kt:2 [definition]"
assert_contains "find_references tags the method call" "$text" "src/App.kt:8 [call]"

echo "=== find_references: import-aware filtering narrows bare references to plausible files ==="
call_tool "find_references" '{"symbol":"login"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_not_contains "find_references drops the unrelated same-named parameter (different package, no import)" "$text" "Unrelated.kt"
assert_contains "find_references keeps the import-connected call site" "$text" "src/Caller.kt:6 [call]"

echo "=== find_references: unknown symbol reports no references ==="
call_tool "find_references" '{"symbol":"TotallyUnknownSymbolZZZ"}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "find_references reports absence cleanly" "$text" "No references found"

echo "=== reindex_code: explicit resync reports a summary ==="
call_tool "reindex_code" '{}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "reindex_code reports a summary" "$text" "Reindex complete"

echo "=== file watcher started successfully ==="
assert_contains "stderr confirms the background file watcher is active" "$(cat "$ERR_FILE")" "File watcher active"

echo "=== freshness: search_code picks up an edit made after the process started ==="
call_tool "search_code" '{"query":"content:BrandNewMarkerZZZ","limit":5}'
advance_response_line
text=$(fetch_text "$RESPONSE_LINE")
assert_contains "marker absent before the edit" "$text" "0 result(s)"

echo "// BrandNewMarkerZZZ" >> "$FIXTURE_DIR/src/App.kt"

# The index is now kept fresh by a background file watcher (debounced), not by a
# synchronous diff inside search_code, so the edit above lands asynchronously.
# Poll instead of asserting on a single immediate call.
text=""
for _ in $(seq 1 30); do
    call_tool "search_code" '{"query":"content:BrandNewMarkerZZZ","limit":5}'
    advance_response_line
    text=$(fetch_text "$RESPONSE_LINE")
    [[ "$text" == *"BrandNewMarkerZZZ"* ]] && break
    sleep 0.1
done
assert_contains "marker present after the edit, picked up by background watcher" "$text" "BrandNewMarkerZZZ"

echo "=== unknown tool name is a clean JSON-RPC error ==="
send "tools/call" '{"name":"does_not_exist","arguments":{}}'
advance_response_line
line=$(fetch_raw "$RESPONSE_LINE")
error_message=$(echo "$line" | jq -r '.error.message // ""')
assert_contains "unknown tool produces a JSON-RPC error" "$error_message" "Unknown tool"

echo
echo "=== Results: $PASS_COUNT passed, $FAIL_COUNT failed ==="
[[ "$FAIL_COUNT" -eq 0 ]]
