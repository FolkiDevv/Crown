# Profile Management Specification

## Purpose

Defines management of proxy profiles and groups: creating, editing, ordering and deleting configurations, organizing them into groups and subscriptions, and importing from or sharing to share links.

## Requirements

### Requirement: Profile persistence
The app SHALL persist proxy profiles in a local database, each with a group membership, display order, traffic statistics, and status markers, and SHALL keep statistics across app restarts.

#### Scenario: Create and restart
- **WHEN** the user creates a profile and restarts the app
- **THEN** the profile is still listed with its saved configuration

### Requirement: Group model
The app SHALL organize profiles into ordered groups; a group is a subscription if and only if it has a subscription URL. Groups SHALL support per-group options including skip-auto-update, auto-clear-unavailable, test/traffic sorting, front and landing proxy chaining, and display order for tab arrangement.

#### Scenario: Create a subscription group
- **WHEN** the user creates a group with a subscription URL
- **THEN** the group is treated as a subscription and can be updated by fetching that URL

### Requirement: Subscription update
The app SHALL fetch a subscription URL and replace the group's profiles with the parsed result, preserving per-profile pins/selection where applicable, recording the last update time and the raw Subscription-UserInfo header.

#### Scenario: Update a subscription
- **WHEN** the user triggers a subscription update and the fetch succeeds
- **THEN** the group's profiles reflect the remote content and the last-update timestamp is refreshed

#### Scenario: Subscription fetch failure
- **WHEN** the fetch or parse fails
- **THEN** existing profiles are kept unchanged and an error is shown

#### Scenario: Subscription document that expands through YAML aliases
- **WHEN** the fetched document is Clash YAML whose aliases would expand into far more values than its text holds (a limit that grows with the text up to a fixed ceiling), whose alias sits inside its own anchor, or which uses a collection as a mapping key
- **THEN** the parse MUST stop without converting the expansion, so the VPN process's memory does not grow noticeably; the document is reported as an unreadable Clash configuration in the import report, existing profiles are kept unchanged, and the same document imported from the clipboard, a file or a share in either interface is reported the same way instead of crashing the app

### Requirement: Import and share
The app SHALL import profiles from share links and raw configs (URL schemes, clipboard, QR scan, file), and SHALL export a profile as a share link or config text.

#### Scenario: Import a share link
- **WHEN** the user imports a supported share link
- **THEN** a profile is created with the decoded parameters and placed in the chosen group

#### Scenario: Text or file nested too deeply
- **WHEN** imported text, a file, a share, a link or a subscription document is JSON or Clash YAML with collections nested more than 96 deep (in the text, or through YAML aliases)
- **THEN** the parse MUST stop before it recurses that deep and the document MUST be reported as unreadable ("The document is nested too deeply"), with nothing added and existing profiles kept unchanged; neither the UI process nor the VPN process crashes, in either interface

#### Scenario: Import sent by another app
- **WHEN** another app shares text (SEND `EXTRA_TEXT`) or sends or opens a file (SEND `EXTRA_STREAM`, VIEW `content://`/`file://`) whose import would add profiles, in the classic interface
- **THEN** the app MUST ask "Import profile?" with the first profile's name and the number of profiles before anything is added, as for a profile link opened from outside, and MUST add nothing on "Cancel"; a subscription link or URL MUST go through its own question ("Add this subscription?" or the URL choice)
- **AND** QR texts MUST be taken only from the app's own scanner (its result, or an intent the app sent itself), never from the `importTexts` extra of another app's intent; an intent reopened from Recents MUST NOT import again; the clipboard, the scanner and "Import from file" in the menu MUST NOT ask

### Requirement: Ordering and pinning
The app SHALL let the user reorder profiles within a group (manual order and sorted orders such as by test result or traffic) and pin profiles to the top.

#### Scenario: Pin a profile
- **WHEN** the user pins a profile
- **THEN** it stays at the top of its group list regardless of other sort orders

### Requirement: Default group
When the app's database is empty, the app SHALL create a Default group automatically on first access, so the profile list is never groupless.

#### Scenario: First access on an empty database

- **WHEN** the app opens the profile list with an empty groups table
- **THEN** a Default group exists and newly imported or created profiles land in it
