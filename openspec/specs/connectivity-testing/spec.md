# Connectivity Testing Specification

## Purpose

Defines connectivity testing behavior: on-demand URL tests and TCP pings against one or many profiles, how results are measured, stored, and displayed, and how tests integrate with sorting and the auto-selector.

## Requirements

### Requirement: Single profile URL test
The user SHALL be able to run a URL test for the currently active or any selected profile; the app SHALL report the measured latency or a timeout/failure state.

#### Scenario: Successful test
- **WHEN** the user runs a URL test on a reachable profile
- **THEN** the measured latency is displayed and stored as the profile's latest result

#### Scenario: Unreachable profile
- **WHEN** the tested profile cannot reach the test URL within the timeout
- **THEN** the profile is shown as unavailable/timeout rather than a latency value

### Requirement: Batch testing
The user SHALL be able to trigger URL tests for all profiles in a group; results SHALL update per profile as each test completes, without blocking the UI.

#### Scenario: Test a whole group
- **WHEN** the user runs a group test
- **THEN** each profile in the group shows its own result as it arrives

### Requirement: Bounded automatic test rounds
Test rounds the app starts on its own, not asked for by the user (the background checks of the simple interface's server list), SHALL be limited in the number of profiles (at most 150 per round) and SHALL NOT start while the list is hidden. URL results of a session SHALL be stored in batches (one transaction per batch of at most 50 results or 1 s), without reading each profile first; the client SHALL still get every result as it arrives, and a background-process death SHALL lose at most the batch not stored yet. A session that hands each stored result to a hook (the auto-selector's ranking) SHALL keep storing each result at once.

#### Scenario: A subscription of thousands of servers
- **GIVEN** a subscription of 3000 servers
- **WHEN** the simple interface's server list is opened
- **THEN** its background session tests at most 150 profiles, and their results are stored a batch per transaction

### Requirement: TCP ping testing
The app SHALL support TCP ping tests measuring connection setup time to a target, as an alternative to URL tests, with results stored and displayed the same way.

#### Scenario: TCP ping a profile
- **WHEN** the user runs a TCP ping on a profile
- **THEN** the connection-time result is stored and displayed for that profile

### Requirement: Result-driven sorting
The user SHALL be able to sort a group's profiles by their latest test result or traffic statistics, and the chosen sort SHALL persist per group.

#### Scenario: Sort by latency
- **WHEN** the user sorts a tested group by latency
- **THEN** profiles are listed from fastest to slowest, with unavailable profiles last

### Requirement: Test configuration
The user SHALL be able to configure test concurrency, timeout, and the test URL in app settings, and tests SHALL respect those values.

#### Scenario: Custom test URL
- **WHEN** the user sets a custom test URL and runs tests
- **THEN** the tests use the configured URL
