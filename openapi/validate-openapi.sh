#!/usr/bin/env bash
#
# validate-openapi.sh — Validate the hoist-core OpenAPI spec
#
# Checks:
#   1. YAML syntax is valid
#   2. Every controller action found in source has a corresponding path in the spec
#   3. (Optional) Full OpenAPI 3.0 schema validation if swagger-cli or redocly is installed
#
# Usage:
#   ./openapi/validate-openapi.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

python3 "$SCRIPT_DIR/validate-openapi.py" "$PROJECT_ROOT"
