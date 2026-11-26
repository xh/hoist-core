### 🐞 Bug Fixes

* A `telemetryPrefix` is no longer applied twice. A span or metric name that already starts with
  `<prefix>.` is used as given. This matches hoist-react.
