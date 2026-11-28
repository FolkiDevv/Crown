# Settings Storage Specification

## Purpose

Defines how application settings are declared, stored, read, and kept consistent across processes: the settings registry contract, typed access through the data store, per-profile versus global setting precedence, and settings-driven UI screens.

## Requirements

### Requirement: Typed settings access
The app SHALL provide typed, named accessors for all settings backed by persistent storage, with defaults applied when a value is unset, and all reads SHALL reflect the latest persisted value across UI and service processes.

#### Scenario: Setting visible in both processes
- **WHEN** the user changes a setting in the UI while a connection service runs
- **THEN** the service observes the new value on its next read

#### Scenario: Settings database that does not open
- **WHEN** the database holding the settings table cannot be opened or migrated and the file is not corrupted
- **THEN** the settings MUST NOT be reset: no default SHALL be read in place of a stored value or written over it, the file SHALL stay as it is, and the app SHALL show the data error screen (UI) or refuse to start the service (`:bg`) as described in `profile-management` «Profile persistence»
- **AND** a rebuild of the file after a corruption SHALL happen only once a copy of it exists, and the user SHALL be told

### Requirement: Settings registry contract
Each supported setting SHALL be registered with its key, type, and default; unrecognized keys encountered during import or migration SHALL be rejected or ignored without corrupting existing settings.

#### Scenario: Unknown key on restore
- **WHEN** a settings import contains a key that is not in the registry
- **THEN** the app does not crash and keeps existing settings intact

### Requirement: Per-profile overrides
The app SHALL let individual profiles override selected global settings; a profile's effective configuration SHALL prefer its override over the global value when the override is set.

#### Scenario: Profile-level override
- **WHEN** a profile overrides a global setting and is started
- **THEN** the running configuration uses the profile's value; other profiles keep the global value

### Requirement: Settings categories
Settings SHALL be organized into user-facing categories including general, appearance, network (TUN/DNS/inbound), routing, core, subscriptions, and testing, and each category screen SHALL only expose settings of that category.

#### Scenario: Open a category
- **WHEN** the user opens a settings category
- **THEN** only that category's settings are shown with their current values

### Requirement: Restart-required hints
For settings that cannot take effect on a running connection, the app SHALL indicate that a reconnect is required for the change to apply.

#### Scenario: Change TUN setting while connected
- **WHEN** the user changes a connection-affecting setting while the VPN runs
- **THEN** the app signals that the change applies after the connection restarts
