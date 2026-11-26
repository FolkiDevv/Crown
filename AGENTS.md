# Notes for coding agents

## Review notes

- Two interfaces do the same jobs: classic (upstream code in app/src/main) and simple (app/src/simple). When simple adds a path classic already has, compare what classic checks or asks first.
- Two processes: the UI and the VPN service (:bg). They share stored settings. A stored change must reach the running service, and writes from both sides can race.
- Android can recreate an activity or kill the process at any time. State kept only in memory is lost.
- Links, intents and files that come from outside the app are untrusted input.
