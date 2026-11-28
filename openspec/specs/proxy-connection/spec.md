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

### Requirement: Restore after the service process died
The app SHALL keep a device-local mark, in the shared settings, that the user wants the connection running: set when the user or an autostart (boot, the package-replaced receiver, always-on VPN) starts the service, and removed by every stop — the main button of either interface, the notification, the quick settings tile, a widget, a shortcut, a failed start, the user turning the VPN off in the system or another VPN app taking over (`onRevoke`). A mark from an earlier boot MUST count as absent. While the mark stands the service SHALL be sticky (`START_STICKY`), and a restart after the service process died SHALL start the selected profile again; with no mark it SHALL stop at once. Because Android may postpone or skip the restart of a sticky service when no other process of the app is alive or not cached, the app MUST NOT depend on it: while the mark stands the service SHALL keep a binding to a watchdog service in a process of its own, and the death of the service process SHALL make the watchdog start the service again at once, also when the UI process is dead or cached. When every process of the app dies at once, a periodic check scheduled with the system (at most every 15 min while the mark stands) SHALL find the mark without a running service and start the restore the same way. The connection SHOULD be back within 15 s of the death; when Android itself delays the start of the service (a restart it already scheduled), it MAY take longer, and it MUST come back without the user once Android starts the service. A restore that does not start MUST leave the foreground, so that no notification reads as connected while the VPN is off; the reason it gave up is the message of the Stopped state. Restores MUST be limited to 3 within 10 minutes and to 3 in a row that never reach Connected (the periodic check starts them further apart than that window), counted in the shared settings before each start; a start by the user or an autostart begins the count anew; past the limit the mark is removed and the user gets a notification «VPN stopped after a failure» with a Connect action. A restore MUST NOT start an activity: without the VPN consent, or when Android refuses the foreground start in the background, the mark is removed and a notification with a Connect action is shown instead; a restore whose start fails reports the error in a notification too. A new service process that finds the mark but no running service (no restart came) SHALL post the notification «VPN disconnected unexpectedly». Each of these reports ends the restore: the mark is removed and the reason is kept as the last start error, which both interfaces show until the user dismisses it or starts again. An update installed from outside the app SHALL bring back a connection that ran before the install.

#### Scenario: Service process killed while connected
- **WHEN** the service process dies while the VPN is connected and the user did not stop it
- **THEN** the service starts again and the selected profile connects again without the user's action

#### Scenario: Service process killed while the UI process is dead
- **WHEN** the UI process is dead or cached and the service process dies while the VPN is connected
- **THEN** the watchdog starts the service again and the selected profile connects without the user opening the app; if Android refuses the start, the notification «VPN disconnected unexpectedly» is shown at once

#### Scenario: Every process of the app killed at once
- **WHEN** the service process, the watchdog and the UI die together while the VPN is connected and the user did not stop it
- **THEN** the periodic check starts the service again within its period (or the user is notified when Android refuses), and no notification of the dead service stays after that

#### Scenario: User connects after a restore gave up
- **WHEN** the user connects again after a restore gave up and the service process then dies
- **THEN** the restore starts again: the count of restores began anew with the user's start

#### Scenario: Restore gives up
- **WHEN** a restore does not start (the limit is reached or the VPN consent is gone)
- **THEN** no notification of the service reads as connected or connecting, and the home screen shows the reason

#### Scenario: Stopped by the user before the process died
- **WHEN** the user stops the connection and the service process is killed afterwards
- **THEN** no connection is started again and no notification is shown

#### Scenario: Profile that kills the process on every start
- **WHEN** every start of the selected profile kills the service process
- **THEN** the service is started again at most 3 times within 10 minutes, then it stays stopped and the notification «VPN stopped after a failure» is shown

#### Scenario: VPN consent revoked
- **WHEN** the system restarts the service after its process died and the VPN consent is gone
- **THEN** no consent screen opens; the notification «VPN not restored» offers to connect

#### Scenario: Installed from a store or adb while connected
- **WHEN** a new version of the app is installed from outside the app while the connection runs
- **THEN** the connection starts again after the install, with or without auto-connect

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
