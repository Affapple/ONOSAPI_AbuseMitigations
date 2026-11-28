# API Abuse Analysis and Mitigation in SDN Controllers
## Problem Definition
Software-Defined Networking (SDN) controllers expose northbound REST APIs through which tenants install flows, submit intents and query network state. In multi-tenant deployments, profile-based access-control lists (ACLs) restrict which endpoints each tenant may use. However, ACLs only decide whether a request is permitted, not how often it is sent, what it contains, or what impact it has on the network.
An authenticated tenant can therefore abuse permissions it legitimately holds. It can flood endpoints, churn flow rules and intents, install broad or conflicting rules, or use read-only access for reconnaissance. Such behaviour can exhaust controller resources, increase flow-installation latency, fill switch flow tables and disrupt other tenants. This project investigates API abuse by authorized tenants and evaluates mitigations that complement ACL-based access control.

## Scope
This project focuses on ONOS and its northbound REST API, particularly endpoints related to flows, intents, devices, and topology. A small multi-tenant SDN environment will be emulated using Mininet, with shared switches and multiple tenant identities accessing the ONOS REST API. 
The project will:
- Implement profile-based ACLs as the baseline defense, with roles such as viewer, tenant operator, and administrator.
- Model abuse scenarios performed by authenticated tenants operating within their assigned permissions.
- Evaluate four abuse scenarios: API flooding and resource exhaustion, flow-rule churn, semantic flow abuse, and intent-based cross-tenant interference.
- Design and implement a mitigation layer that complements ACLs using per-tenant rate limits, resource quotas, payload validation, conflict detection, and adaptive throttling.
- Measure security and performance impact across three configurations: no controls, ACLs only, and ACLs plus the proposed mitigation layer.
- Southbound attacks against OpenFlow, controller-host compromise, stolen administrator credentials, web GUI attacks, and ONOS cluster-level attacks are outside the scope of this project.

## Objective
The main objectives are to:
1. Develop a threat model for authorized API abuse in a multi-tenant ONOS environment.
2. Implement ACL-based access control as a baseline and demonstrate which abuse scenarios it prevents and which it does not.
3. Implement a lightweight API mitigation layer providing tenant identification, rate limiting, quotas, request validation, and policy-conflict checking.
4. Quantify the impact of API abuse on controller CPU utilization, memory/JVM heap consumption, flow-installation latency, HTTP response latency, and legitimate tenant throughput.
5. Evaluate whether the mitigation layer reduces abusive behavior while preserving normal SDN operations with acceptable overhead.

