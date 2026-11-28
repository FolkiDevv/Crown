# Proxy Connection Specification

## Purpose

Defines the lifecycle and system integration of the VPN/proxy connection service: state machine, foreground notification, IPC binding from the UI process, and system entry points (quick settings tile, boot receiver, broadcast actions).

## Requirements

### Requirement: Connection state machine
The connection service SHALL expose exactly the states Connecting, Connected, Stopping, Stopped, and SHALL transition through them in order when starting or stopping a profile. The service SHALL never report Idle; Idle is a UI-only state shown before any service is bound.

#### Scenario: Start a profile
- **WHEN** the user starts a profile from the UI
- **THEN** the service reports Connecting, and on success reports Connected; failures surface as an error state with a user-visible message

#### Scenario: Stop a running connection
- **WHEN** the user stops a connected service
- **THEN** the service reports Stopping and then Stopped, and the VPN interface is torn down

### Requirement: In-place restart keeps traffic inside the tunnel
Switching the profile of a running service or reloading it (from the UI, the notification, a widget, the auto-selector, the network regime watcher or a subscription refresh) MUST NOT let traffic leave around the tunnel while the VPN interface configuration stays the same, and SHALL keep any gap to what Android imposes when it changes. The service SHALL build the next core while the running one still carries traffic, and SHALL close the running core only once the next one is built. The VPN interface of the running core MUST stay the VPN network until the next core takes it over: when the next core asks for an interface with the same configuration (addresses, routes, DNS, MTU, app lists with the uids their packages have at that moment, so a listed app installed or removed since counts as a change, HTTP proxy, bypass and metered flags), it SHALL get the same interface without a new `VpnService.Builder.establish()`, so the interface never changes; otherwise `establish()` replaces it. The kept interface MUST be closed when the restart does not end in a running core: the next core fails to start, a stop arrives during the restart, or the service stops. When the next core cannot be built, the restart SHALL fall back to closing the tunnel first and starting as a plain start. The app SHALL own the file descriptor of each VPN interface it establishes; the core works on its own duplicate and never takes the app's one down. A changed interface configuration is only as seamless as Android makes it: `establish()` resets the old interface before the network has the new one's routes, and a change of the allowed or disallowed apps or of the bypass flag makes Android disconnect the old VPN network before the new one connects (AOSP `Vpn.updateLinkPropertiesInPlaceIfPossible`), and a change of the metered flag, or of the uids of unchanged app lists (a listed app installed, removed or reinstalled since), MUST close the old interface before the next `establish()`, as a plain restart, because Android sets that flag and the uids only when it creates the network and hands the network over whenever the package names are the same (AOSP `Vpn.agentConnect`, `Vpn.updateLinkPropertiesInPlaceIfPossible`); the app cannot close those gaps, and «Always-on VPN» with «Block connections without VPN» is the protection there.

#### Scenario: Server switch while connected
- **WHEN** the user selects another server while the VPN is connected
- **THEN** no connection leaves the device outside the VPN during the switch, the VPN network stays in the system's network list, and the new core runs on the same VPN interface

#### Scenario: Restart that changes the app lists, the bypass or the metered flag
- **WHEN** a restart changes the allowed or disallowed apps of the VPN (also by a listed app installed or removed since), its bypass flag or its metered flag
- **THEN** the VPN network is replaced and carries the new setting, a short gap is possible, and with «Block connections without VPN» on no connection leaves outside the VPN

#### Scenario: New core fails to start
- **WHEN** a restart's next core fails to start
- **THEN** the service stops with the error, and no VPN interface without a core is left behind

#### Scenario: Stop during a restart
- **WHEN** the user stops the service while a restart builds or starts the next core
- **THEN** the build is cancelled, the service ends Stopped and the kept VPN interface is closed

### Requirement: Foreground service notification
While the service runs it SHALL present a foreground notification showing the current profile and traffic statistics, and SHALL offer quick actions including stop and switch to next/previous profile.

#### Scenario: Statistics visible in notification
- **WHEN** a connection is established and traffic flows
- **THEN** the notification shows updated uplink/downlink totals

### Requirement: UI process binding
The UI process SHALL communicate with the running service over an AIDL connection, receiving state changes, traffic updates, and test results, and SHALL degrade gracefully (show Idle) when no service is running.

#### Scenario: UI reconnects to a running service
- **WHEN** the UI process starts while a service is already running
- **THEN** it binds, receives the current state, and reflects it without restarting the service

### Requirement: External control actions
The app SHALL support broadcast/Intent actions to stop the service, reload the running configuration, switch to the next or previous profile, and switch to a profile by id, invokable from the notification, widgets, and quick settings tile.

#### Scenario: Quick settings tile toggle
- **WHEN** the user taps the quick settings tile with no active connection
- **THEN** the app starts the currently selected profile; tapping again stops it

### Requirement: VPN and proxy modes
The service SHALL support VPN mode (TUN device capturing device traffic, with per-app allow/bypass lists) and proxy-only mode, selected by the user's service mode setting.

#### Scenario: VPN mode with per-app proxy
- **WHEN** the user enables per-app proxy with an allow list and starts the VPN
- **THEN** only traffic of the listed apps is routed through the tunnel
