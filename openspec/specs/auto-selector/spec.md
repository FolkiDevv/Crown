# Auto Selector Specification

## Purpose

Defines the auto-selector behavior: periodically or manually testing candidate profiles within a scope and automatically switching the active connection to the best available node.

## Requirements

### Requirement: Automatic node selection
The app SHALL, when auto-selector mode is enabled for the running connection, test candidate profiles and switch the active node to the best-scoring available candidate without user intervention.

#### Scenario: Current node degrades
- **WHEN** the active node fails its test while another candidate passes
- **THEN** the app switches the connection to a passing candidate and shows the switch in the UI

### Requirement: Failover time
When the active node stops answering (connections refused, or accepted with no reply) while another candidate is
confirmed working, the selector SHALL move new connections to that candidate within 20 s of the first failed
connection. The app's managed selectors SHALL use check settings chosen for that (passive judging by real connections,
a short watch interval of the active node), and the app MAY ask the running core for an immediate re-check of every
member when its own check through the selector fails.

#### Scenario: Server stops answering with a confirmed standby
- **GIVEN** a running auto-selector with two members, both confirmed working
- **WHEN** the active member starts accepting connections without ever replying
- **THEN** within 20 s new connections go through the other member

### Requirement: Candidate scope
The user SHALL be able to constrain auto-selector to a group and to a minimum number of candidates, and the selector SHALL only switch among profiles within that scope.

#### Scenario: Restricted scope
- **WHEN** auto-selector is scoped to one group
- **THEN** nodes outside that group are never selected

### Requirement: Manual pinning
The user SHALL be able to pin a specific node, suspending automatic switching; the pin SHALL be releasable by the user and by a control action, after which automatic selection resumes.

#### Scenario: Pin and release
- **WHEN** the user pins a node and later releases the pin
- **THEN** automatic selection resumes on the next evaluation

### Requirement: Selector state visibility
The auto-selector SHALL expose its current choice and last test results to the UI and connection notification, so the user can see which node is active and why.

#### Scenario: Selector status screen
- **WHEN** the user opens the auto-selector status screen
- **THEN** current node, candidates, and latest test latencies are shown
