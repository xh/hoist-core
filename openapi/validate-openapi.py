#!/usr/bin/env python3
"""
validate-openapi.py — Validate hoist-core OpenAPI spec against controller source.

Checks:
  1. YAML syntax is valid
  2. Every public controller action has a corresponding path in the spec
  3. Reports coverage stats

Usage:
  python3 openapi/validate-openapi.py [project_root]
"""
import os
import re
import sys
import yaml
from pathlib import Path

# ── Config ──
SKIP_CLASSES = {'UrlMappings', 'AccessInterceptor'}
SKIP_ACTIONS = {'notFound'}  # 404 handler, not a real endpoint
ABSTRACT_PATTERN = re.compile(r'abstract\s+class\s+')
REST_TARGET_PATTERN = re.compile(r'static\s+restTarget\s*=')
PUBLIC_ACTION_PATTERN = re.compile(r'^\s+def\s+(\w+)\s*\(', re.MULTILINE)
PRIVATE_ACTION_PATTERN = re.compile(r'(private|protected)\s+(def|void)\s+(\w+)\s*\(', re.MULTILINE)

GREEN = '\033[0;32m'
RED = '\033[0;31m'
YELLOW = '\033[1;33m'
NC = '\033[0m'


def to_url_name(class_name: str) -> str:
    """Convert FooBarController to fooBar."""
    name = class_name.replace('Controller', '')
    return name[0].lower() + name[1:]


def extract_actions(file_path: Path) -> tuple:
    """Extract class info and public actions from a controller file."""
    content = file_path.read_text()
    class_name = file_path.stem

    if class_name in SKIP_CLASSES:
        return None, None, []

    if ABSTRACT_PATTERN.search(content):
        return None, None, []

    is_rest = bool(REST_TARGET_PATTERN.search(content))

    # Find private/protected methods to exclude
    private_methods = set()
    for m in PRIVATE_ACTION_PATTERN.finditer(content):
        private_methods.add(m.group(3))

    # Find public action methods
    actions = []
    for m in PUBLIC_ACTION_PATTERN.finditer(content):
        action_name = m.group(1)
        if action_name not in private_methods and action_name not in SKIP_ACTIONS:
            actions.append(action_name)

    return class_name, is_rest, actions


def check_action_in_spec(class_name: str, action: str, url_name: str,
                         is_rest: bool, spec_paths: set) -> bool:
    """Check if a controller action has a corresponding spec path."""
    # REST CRUD actions
    if is_rest:
        rest_base = f'/rest/{url_name}'
        if action in ('create', 'read', 'update'):
            return rest_base in spec_paths
        if action == 'delete':
            return f'{rest_base}/{{id}}' in spec_paths
        if action in ('bulkUpdate', 'bulkDelete', 'lookupData'):
            return f'{rest_base}/{action}' in spec_paths

    # ProxyImplController special case — mapped via UrlMappings to /proxy/{name}/{url}
    if url_name == 'proxyImpl':
        return any(p.startswith('/proxy/') for p in spec_paths)

    # Standard action paths
    if f'/{url_name}/{action}' in spec_paths:
        return True

    # index action → /{controller}/index or /{controller}
    if action == 'index':
        return f'/{url_name}' in spec_paths or f'/{url_name}/index' in spec_paths

    return False


def main():
    project_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('.')
    spec_file = project_root / 'openapi' / 'openapi.yaml'
    controller_dir = project_root / 'grails-app' / 'controllers' / 'io' / 'xh' / 'hoist'

    print('═' * 55)
    print(' Hoist Core OpenAPI Spec Validation')
    print('═' * 55)
    print()

    pass_count = 0
    fail_count = 0
    warn_count = 0

    # ── 1. YAML syntax ──
    print('1. YAML Syntax')
    try:
        with open(spec_file) as f:
            spec = yaml.safe_load(f)
        print(f'  {GREEN}✓{NC} YAML syntax is valid')
        pass_count += 1
    except Exception as e:
        print(f'  {RED}✗{NC} YAML syntax error: {e}')
        fail_count += 1
        sys.exit(1)

    spec_paths = set(spec.get('paths', {}).keys())
    print(f'  {GREEN}✓{NC} Spec contains {len(spec_paths)} path entries')
    pass_count += 1
    print()

    # ── 2. Controller cross-reference ──
    print('2. Controller Cross-Reference')
    missing = []

    controller_files = sorted(controller_dir.rglob('*Controller.groovy'))
    for cf in controller_files:
        class_name, is_rest, actions = extract_actions(cf)
        if not class_name:
            continue

        url_name = to_url_name(class_name)

        for action in actions:
            found = check_action_in_spec(class_name, action, url_name, is_rest, spec_paths)
            if found:
                print(f'  {GREEN}✓{NC} {class_name}.{action}')
                pass_count += 1
            else:
                print(f'  {RED}✗{NC} {class_name}.{action} → MISSING (/{url_name}/{action})')
                fail_count += 1
                missing.append(f'{class_name}.{action}')

    print()

    # ── 3. Schema validation hint ──
    print('3. Schema Validation')
    print(f'  {YELLOW}⚠{NC} For full OpenAPI 3.0 schema validation, run:')
    print(f'     npx --yes @redocly/cli lint openapi/openapi.yaml')
    print(f'     # or: npx --yes swagger-cli validate openapi/openapi.yaml')
    warn_count += 1
    print()

    # ── Results ──
    print('═' * 55)
    print(f' Results: {GREEN}{pass_count} passed{NC}, {YELLOW}{warn_count} warnings{NC}, {RED}{fail_count} failed{NC}')
    print('═' * 55)

    if missing:
        print()
        print(f'{RED}Missing endpoints to add to openapi.yaml:{NC}')
        for ep in missing:
            print(f'  - {ep}')

    sys.exit(min(fail_count, 255))


if __name__ == '__main__':
    main()
