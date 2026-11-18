The idea in one sentence

Tenants aren't allowed to talk to ONOS directly. Every request goes through our app (the gateway), which runs it through a series of checks. If every check passes, the gateway installs the flow or intent in ONOS for the tenant.

The vocabulary

┌───────────────┬───────────────────────────────────────────────────────────────────────────────────┐
│     Word      │                              Meaning in this project                              │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│               │ A customer sharing the network. We have two: t1 (hosts h1a, h1b, subnet           │
│ Tenant        │ 10.0.1.0/24) and t2 (hosts h2a, h2b, subnet 10.0.2.0/24). Each tenant has two     │
│               │ logins: t1-op (operator, can write) and t1-view (viewer, read only).              │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│               │ Belongs to the caller's tenant: its hosts' MACs/IPs, the switch ports its hosts   │
│ Owned         │ plug into, its subnet. Most checks come down to "does this request only touch     │
│               │ what you own?"                                                                    │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ ACL           │ "Are you allowed to call this endpoint at all, and on these switches and ports?"  │
│               │ This is what replaced the Karaf role files from Lab2.                             │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ Rate limit    │ "Are you sending requests too fast?" Each tenant has a budget of about 10         │
│               │ requests per second.                                                              │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ Quota         │ "Do you already hold too much?" Caps on total flows, flows per switch, intents,   │
│               │ and how many adds/deletes per minute.                                             │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ Validation    │ "Is this rule sane on its own?" Priority in range, it expires, it isn't a         │
│               │ catch-all, it doesn't send packets to the controller.                             │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ Conflict      │ "Would this rule interfere with the other tenant?" Spoofed source address,        │
│               │ matching t2's destination, overlapping t2's rules.                                │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ Throttle      │ "Have you been misbehaving?" Every rejection adds points; enough points slows you │
│ (adaptive)    │  down, then pauses your writes, then blocks you. This is your Lab2 escalation     │
│               │ idea.                                                                             │
├───────────────┼───────────────────────────────────────────────────────────────────────────────────┤
│ Audit         │ "Did anything get into ONOS without going through the gateway?" It watches ONOS   │
│               │ and flags rules that bypassed us. It's a watchdog, not a check on requests.       │
└───────────────┴───────────────────────────────────────────────────────────────────────────────────┘

One request, start to finish

Say t1-op sends POST /onos/gw/flows with one flow rule. The checks run in this order:

1. Login (Karaf)             wrong password?                → 401
2. Who are you? (tenant/)    t1-op → tenant t1, operator
3. Throttle (throttle/)      t1 currently blocked/paused?   → 403 / 429
4. Rate limit (ratelimit/)   t1 out of request budget?      → 429
5. ACL (acl/)                allowed endpoint? own switch/ports?   → 403
6. Validation (validation/)  rule sane and confined to t1's traffic?  → 400
7. Conflict (conflict/)      would it touch t2's traffic?   → 409
8. Quota (quota/)            would t1 hold too many flows?  → 409
9. Install in ONOS, labelled as t1's

Whatever the outcome, any rejection also adds points to t1's throttle score. Separately, the audit module keeps watching ONOS for rules that skipped all of this.

How the folders map to your five goals

┌───────────────────────────────┬─────────────┬──────────────────────────────┐
│           Your goal           │   Folder    │          Main file           │
├───────────────────────────────┼─────────────┼──────────────────────────────┤
│ Per-tenant rate limits        │ ratelimit/  │ TokenBucketLimiter.java      │
├───────────────────────────────┼─────────────┼──────────────────────────────┤
│ Resource quotas               │ quota/      │ QuotaManager.java            │
├───────────────────────────────┼─────────────┼──────────────────────────────┤
│ Payload validation            │ validation/ │ PayloadValidator.java        │
├───────────────────────────────┼─────────────┼──────────────────────────────┤
│ Conflict detection / spoofing │ conflict/   │ ConflictDetector.java        │
├───────────────────────────────┼─────────────┼──────────────────────────────┤
│ Adaptive throttling           │ throttle/   │ ThrottleManager.java         │
├───────────────────────────────┼─────────────┼──────────────────────────────┤
│ Roles + ACLs (Track A)        │ acl/        │ PathAcl.java, Ownership.java │
└───────────────────────────────┴─────────────┴──────────────────────────────┘

The rest is plumbing:
- tenant/: reads the list of tenants, hosts and accounts (tenants.json).
- rest/: the HTTP endpoints. FlowsResource and IntentsResource are the important ones. GatewayFilter runs steps 2–5 above on every request.
- GatewayManager.java (868 lines, the biggest file): starts everything up, holds the settings, and has the on/off switch for each module. Those switches are how we get the three evaluation configs: everything off, only the ACL on, or everything on.
- audit/: the bypass watchdog (B7, still in progress).

Suggested reading order (about 1,000 lines, not 6,800)

1. rest/GatewayFilter.java: the front door. Shows steps 2–5.
2. rest/FlowsResource.java: shows steps 5–9 for flows.
3. Then one module per sitting, in pipeline order: ratelimit/TokenBucketLimiter, validation/PayloadValidator, conflict/ConflictDetector, quota/QuotaManager, throttle/ThrottleManager.

You can skip GatewayManager, TenantRegistry and the *Settings/*Limits files at first; they're wiring and config parsing.

Would it help if I made this a one-page diagram, or walked through one of those files with you line by line?