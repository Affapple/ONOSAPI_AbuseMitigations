#!/bin/sh
# B0 integration test. Runs ON THE VM (read-only: no lock needed).
#   sh ~/gw/b0-whoami.sh
# Checks GET /onos/gw/whoami for each account against the plan's B0 "Test" line.
# Tenant accounts that Karaf does not know yet (401) are reported as PENDING,
# not as failures, because Track A creates them.
GW=http://localhost:8181/onos/gw/whoami
fail=0

# expect <user:pass> <http code> [<python expr over j (parsed JSON) that must be True>]
expect() {
    body=$(curl -s -u "$1" -w '\n%{http_code}' "$GW")
    code=$(printf '%s' "$body" | tail -n1)
    json=$(printf '%s' "$body" | sed '$d')
    if [ "$code" != "$2" ]; then
        echo "FAIL  $1 -> HTTP $code (want $2)"; fail=1; return
    fi
    if [ -n "${3:-}" ]; then
        if printf '%s' "$json" | python3 -c "import json,sys; j=json.load(sys.stdin); sys.exit(0 if ($3) else 1)"; then
            echo "ok    $1 -> $code $json"
        else
            echo "FAIL  $1 -> $code $json  (want: $3)"; fail=1
        fi
    else
        echo "ok    $1 -> $code"
    fi
}

# tenant account: PENDING if Karaf does not know it yet
expect_tenant() {
    code=$(curl -s -o /dev/null -w '%{http_code}' -u "$1" "$GW")
    if [ "$code" = 401 ]; then
        echo "PEND  $1 -> 401 (account not in users.properties yet?)"
    else
        expect "$@"
    fi
}

expect onos:rocks   200 'j["user"]=="onos" and j["gatewayRole"]=="admin" and j["tenant"] is None and "admin" in j["roles"]'
expect karaf:karaf  200 'j["user"]=="karaf" and j["gatewayRole"]=="none" and j["tenant"] is None and "admin" in j["roles"]'
# guest is being removed from the overlays by Track A: 401 (removed) or 200 with role none are both fine.
gcode=$(curl -s -o /dev/null -w '%{http_code}' -u guest:guest "$GW")
if [ "$gcode" = 401 ]; then echo "ok    guest:guest -> 401 (account removed)"; else expect guest:guest 200 'j["gatewayRole"]=="none" and j["tenant"] is None'; fi
expect onos:wrong   401
expect guest:wrong  401
expect nosuch:user  401
code=$(curl -s -o /dev/null -w '%{http_code}' "$GW"); [ "$code" = 401 ] && echo "ok    (no credentials) -> 401" || { echo "FAIL  (no credentials) -> $code"; fail=1; }

expect_tenant t1-op:t1-op-pw     200 'j["user"]=="t1-op" and j["tenant"]=="t1" and j["gatewayRole"]=="operator" and j["appId"]=="lu.uni.sdn.gw.t1" and j["roles"]==["tenant"]'
expect_tenant t1-view:t1-view-pw 200 'j["tenant"]=="t1" and j["gatewayRole"]=="viewer"'
expect_tenant t2-op:t2-op-pw     200 'j["tenant"]=="t2" and j["gatewayRole"]=="operator" and j["appId"]=="lu.uni.sdn.gw.t2"'
expect_tenant t2-view:t2-view-pw 200 'j["tenant"]=="t2" and j["gatewayRole"]=="viewer"'
expect_tenant gw-svc:gw-svc-pw   200 'j["tenant"] is None and j["gatewayRole"]=="service"'
if [ "$(curl -s -o /dev/null -w '%{http_code}' -u t1-op:t1-op-pw "$GW")" = 200 ]; then
    expect t1-op:wrong-pw 401
else
    echo "PEND  t1-op:wrong-pw (t1-op does not exist yet)"
fi

[ $fail = 0 ] && echo "B0 whoami: PASS (see PEND lines)" || echo "B0 whoami: FAIL"
exit $fail
