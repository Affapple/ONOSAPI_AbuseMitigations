# API Abuse Analysis & Mitigation in SDN Controllers
## Report Structure & Index

---

## **1. Problem Definition**
- Introduction to the problem area: API abuse in multi-tenant SDN environments
- Key concepts: northbound REST APIs, authenticated tenants, ACL limitations
- Background: ONOS controller, multi-tenant deployments, existing access controls

---

## **2. Objectives & Goals**
- What you planned to solve (5 key objectives):
  1. Develop a threat model for authorized API abuse in a multi-tenant ONOS environment
  2. Implement ACL-based access control as a baseline and demonstrate which abuse scenarios it prevents and which it does not
  3. Implement a lightweight API mitigation layer providing tenant identification, rate limiting, quotas, request validation, and policy-conflict checking
  4. Quantify the impact of API abuse on controller CPU utilization, memory/JVM heap consumption, flow-installation latency, HTTP response latency, and legitimate tenant throughput
  5. Evaluate whether the mitigation layer reduces abusive behavior while preserving normal SDN operations with acceptable overhead

---

## **3. Method**
- How the problem was investigated: simulation-based experimental approach
- Multi-tenant Mininet environment with ONOS controller
- Measurement collection methodology across three configurations:
  - Configuration 1: No controls (baseline)
  - Configuration 2: ACLs only
  - Configuration 3: ACLs + mitigation layer
- Metrics tracked: CPU, memory, latency, throughput, flow-rule installation time

---

## **4. Design**

### **4.1 Threat Model & Attack Scenarios**
- Authorized tenant abuse capabilities
- Four abuse scenarios:
  1. API flooding and resource exhaustion
  2. Flow-rule churn
  3. Semantic flow abuse
  4. Intent-based cross-tenant interference
- Attack surface and assumptions
- Impact classification

### **4.2 Lab Setup**
- Mininet topology configuration (topology details, shared switches)
- ONOS controller deployment and configuration
- Tenant role definitions:
  - Viewer (read-only access)
  - Tenant operator (limited write access)
  - Administrator (full access)
- Multi-tenant authentication mechanism

### **4.3 Implementation**
- **ACL baseline system**: Profile-based access control implementation
- **Abuse injection framework**: How abuse scenarios are simulated
- **Mitigation layer components**:
  - Per-tenant rate limiting
  - Resource quotas
  - Request payload validation
  - Conflict detection
  - Adaptive throttling
- **Monitoring infrastructure**: Data collection and logging

---

## **5. Results & Analysis**
- Summarized data (tables and figures)
  - CPU utilization across configurations
  - Memory/JVM heap consumption
  - Flow-installation latency
  - HTTP response latency
  - Legitimate tenant throughput
- Findings per configuration and scenario:
  - Baseline (no controls): Full impact of abuse
  - ACL only: Which scenarios are prevented, which persist
  - ACLs + mitigation: Effectiveness of each mitigation component
- Analysis and explanation of results

---

## **6. Reflection & Conclusion**
- Sum up the main points of the report
- Clearly relate findings back to the 5 objectives
- Identify limitations of the approach
- Discuss impact and implications for multi-tenant SDN security
- Future work and improvements

---
