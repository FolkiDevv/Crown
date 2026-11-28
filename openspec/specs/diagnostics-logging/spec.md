# Diagnostics Logging Specification

## Purpose

Defines diagnostics behavior: in-app log viewing, core log capture, log export and sharing, and redaction of sensitive information before logs leave the device.

## Requirements

### Requirement: In-app log viewer
The app SHALL provide a log viewer that displays the running core's log output in real time, with actions to pause, clear, and filter the view.

#### Scenario: View live logs
- **WHEN** the user opens the log viewer while connected
- **THEN** new core log lines appear as they are produced

### Requirement: Log export and sharing
The user SHALL be able to export the full log to a file and share it via the system share sheet, including core logs from the current and previous sessions as available.

#### Scenario: Export a log file
- **WHEN** the user taps export in the log viewer
- **THEN** a log file is created and offered for sharing

### Requirement: Sensitive data redaction
Before a log is exported or shared, the app SHALL redact sensitive fields — server addresses, credentials, UUIDs, passwords, and private keys — so that shared logs do not leak connection secrets.

#### Scenario: Redacted export
- **WHEN** the user shares an exported log that contains a profile's password
- **THEN** the password is replaced by a placeholder in the shared content

### Requirement: Crash and error reporting path
Unexpected service errors SHALL be captured with stack traces in the log so the user can include them in exported diagnostics, without sending any data off-device automatically.

An uncaught exception in any process of the app (the app's process, the service processes `:bg` and `:watch`) MUST end that process: after logging it and writing a small marker file (process, time, exception class; no database access), the handler SHALL pass the exception to Android's own handler, which records the crash and kills the process. A crashed process MUST NOT start an activity and the user's task MUST NOT be cleared. The report SHALL be offered later: a crash of a service process (`:bg`, the restore watchdog `:watch`) by a notification («VPN service stopped because of an error» for `:bg`) whose tap shares the redacted log, posted by the next `:bg` or the next opening of either interface; a crash of the app's process by a dialog «Send log» when either interface opens next (it stays until answered, also across a recreated activity). A crash of the app's process within 60 seconds of the previous one MUST NOT open the dialog; it gets the notification instead, so a report that crashes cannot loop. When the connection restore reports the lost connection while the crash notification is shown or a service process's crash is not reported yet, one notification SHALL carry both actions. A crash while the log is being shared leaves no marker.

#### Scenario: Service error captured
- **WHEN** the connection service hits an unexpected error
- **THEN** the error and stack trace appear in the log and no data is transmitted automatically

#### Scenario: Crash of the service process in the background
- **WHEN** the main thread of `:bg` throws an uncaught exception while no window of the app is visible
- **THEN** the process ends, no activity is started, and the next service process posts the notification «VPN service stopped because of an error», whose tap opens the log share

#### Scenario: Crash of the app process
- **WHEN** the app's process crashes and the user opens either interface afterwards
- **THEN** the interface works and asks whether to send the log; the question is not shown again once answered

#### Scenario: Crash on every opening
- **WHEN** the app's process crashes again within 60 seconds of its previous crash
- **THEN** the app is not restarted by itself and the next opening shows no dialog, only the notification
