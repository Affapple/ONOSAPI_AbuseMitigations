#!/usr/bin/env python3
"""Live checks for the B1 audit fixes. Runs ON THE VM, under the ONOS lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b1-fixes.py

1. Treatment allowlist: METER and TABLE instructions (stored outside
   allInstructions() by ONOS) are rejected with 403.
2. aclEnabled=false never opens admin-only surfaces; it only turns off tenant
   ownership checks and read filtering. The toggle is restored at the end.
"""
import sys

from gwlib import FAILS, BASE, GW, S2, call, check, expect, flow, saved_config, set_cfg, summary


def main():
    print("== 1. treatment allowlist (live)")
    check("METER instruction", "t1-op", "POST", "/flows", 403, "resource_not_owned",
          body={"flows": [flow(S2, 1, 3, [{"type": "METER", "meterId": 1}])]})
    check("TABLE instruction", "t1-op", "POST", "/flows", 403, "instruction_not_allowed",
          body={"flows": [flow(S2, 1, 3, [{"type": "TABLE", "tableId": 1}])]})
    check("GROUP instruction", "t1-op", "POST", "/flows", 403, "resource_not_owned",
          body={"flows": [flow(S2, 1, 3, [{"type": "GROUP", "groupId": 1}])]})
    check("MPLS push (L2MODIFICATION not allowlisted)", "t1-op", "POST", "/flows", 403, "instruction_not_allowed",
          body={"flows": [flow(S2, 1, 3, [{"type": "L2MODIFICATION", "subtype": "MPLS_PUSH",
                                           "ethernetType": "0x8847"}])]})
    # meter/table batch with one good flow first: whole batch rejected, nothing installed
    before = len(call("t1-op", "GET", GW + "/flows")[1]["flows"])
    check("batch [good, METER]", "t1-op", "POST", "/flows", 403, "resource_not_owned",
          body={"flows": [flow(S2, 1, 3), flow(S2, 1, 3, [{"type": "METER", "meterId": 1}])]})
    after = len(call("t1-op", "GET", GW + "/flows")[1]["flows"])
    expect("nothing installed by the rejected batch", before == after, "(%d -> %d t1 flows)" % (before, after))

    print("== 2. aclEnabled=false keeps admin-only surfaces closed")
    _, allf, _ = call("onos", "GET", BASE + "/onos/v1/flows/" + S2)
    core = [f for f in allf["flows"] if not f["appId"].startswith("lu.uni.sdn.gw.")]
    expect("found a non-tenant (core) flow to target", bool(core),
           "%s/%s appId=%s" % (S2, core[0]["id"], core[0]["appId"]) if core else "")
    with saved_config(["aclEnabled"]):
        set_cfg({"aclEnabled": False})
        for u in ("t1-view", "t1-op"):
            check("v1/configuration (exposes serviceAccountPassword)", u, "GET", "/v1/configuration", 403,
                  "acl_denied")
            check("v1/configuration/<component>", u, "GET",
                  "/v1/configuration/lu.uni.sdn.apigateway.GatewayManager", 403, "acl_denied")
            check("v1/flows (non-allowlisted read)", u, "GET", "/v1/flows", 403, "acl_denied")
            check("v1/applications", u, "GET", "/v1/applications", 403, "acl_denied")
            check("v1 write", u, "POST", "/v1/flows", 403, "acl_denied", body={"flows": []})
            check("v1 delete", u, "DELETE", "/v1/flows/%s/1" % S2, 403, "acl_denied")
            check("admin/*", u, "GET", "/admin/tenants", 403, "acl_denied")
            if core:
                check("DELETE core flow via /gw/flows", u, "DELETE", "/flows/%s/%s" % (S2, core[0]["id"]),
                      403, "not_owner")
        # what the toggle DOES relax (positive controls)
        check("t2-view reads t1's flows (filtering off)", "t2-view", "GET", "/flows?tenant=t1", 200)
        st, js, _ = call("t1-view", "GET", GW + "/v1/hosts")
        macs = sorted(h["mac"] for h in js["hosts"]) if st == 200 else []
        expect("t1-view sees all hosts (host filtering off)", st == 200 and len(macs) == 4, str(macs))
        check("unknown account still denied", "karaf", "GET", "/flows", 403, "unknown_account")
    if core:
        _, again, _ = call("onos", "GET", BASE + "/onos/v1/flows/%s/%s" % (S2, core[0]["id"]))
        expect("core flow still present", isinstance(again, dict) and len(again.get("flows", [])) == 1)
    check("toggle back on: v1/hosts filtered again", "t1-view", "GET", "/v1/hosts", 200)
    st, js, _ = call("t1-view", "GET", GW + "/v1/hosts")
    expect("t1-view sees only t1 hosts again", sorted(h["mac"] for h in js["hosts"])
           == ["00:00:00:00:01:01", "00:00:00:00:01:02"])
    return summary("B1 fixes")


if __name__ == "__main__":
    # B6 off for the run: this suite sends rejected requests on purpose.
    import gwlib
    with gwlib.saved_config(["adaptiveEnabled"]):
        gwlib.set_cfg({"adaptiveEnabled": False})
        rc = main()
    sys.exit(rc or (1 if gwlib.FAILS else 0))
