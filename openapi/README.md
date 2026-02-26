# Hoist Core OpenAPI Specification

This directory contains the OpenAPI 3.0 specification for hoist-core's HTTP API, covering all
controller endpoints provided by the framework plugin.

## Files

| File | Purpose |
|------|---------|
| `openapi.yaml` | The OpenAPI 3.0.3 spec — 122 paths across 30 tags |
| `validate-openapi.sh` | Shell wrapper to run validation |
| `validate-openapi.py` | Python validation script — cross-references spec against controller source |

## Using the Spec

### Swagger UI

Serve the spec with any Swagger UI instance:

```bash
# Quick local viewer via Docker
docker run -p 8080:8080 -e SWAGGER_JSON=/spec/openapi.yaml \
  -v $(pwd)/openapi:/spec swaggerapi/swagger-ui

# Or use the online editor at https://editor.swagger.io
# (paste/upload openapi.yaml)
```

### Code Generation / Mocking

The spec can be used with standard OpenAPI tooling:

```bash
# Generate a mock server with Prism
npx @stoplight/prism-cli mock openapi/openapi.yaml

# Generate client SDKs with openapi-generator
npx @openapitools/openapi-generator-cli generate \
  -i openapi/openapi.yaml -g typescript-fetch -o generated/client
```

### Redoc Documentation

```bash
npx @redocly/cli preview-docs openapi/openapi.yaml
```

## Validation

Run the validation script to verify the spec matches the current codebase:

```bash
# From project root
./openapi/validate-openapi.sh

# Or directly
python3 openapi/validate-openapi.py /path/to/hoist-core
```

The script checks:
1. **YAML syntax** — ensures the file parses correctly
2. **Endpoint coverage** — scans every `*Controller.groovy` file, extracts public actions,
   and verifies each has a corresponding path entry in the spec
3. **Schema validation** (optional) — suggests running `@redocly/cli lint` for full
   OpenAPI 3.0 schema compliance

For full schema validation, install one of:
```bash
npm install -g @redocly/cli         # Recommended
npm install -g swagger-cli          # Alternative
```

## Updating the Spec

When adding or modifying controller endpoints in hoist-core:

### Adding a New Endpoint

1. Add the endpoint to the appropriate section in `openapi.yaml`:
   - **Core endpoints** (XhController, XhViewController) → top of paths section
   - **Admin REST CRUD** (extends AdminRestController) → "ADMIN REST CRUD" section
   - **Admin non-REST** → "ADMIN NON-REST ENDPOINTS" section
   - **Cluster admin** → "ADMIN CLUSTER ENDPOINTS" section

2. Follow the existing patterns:
   - Use the correct tag from the `tags` list
   - Include `operationId` as `{controllerUrlName}.{action}`
   - Document parameters, request body, and response schema
   - Note required roles in the `description` field

3. Run validation: `./openapi/validate-openapi.sh`

### Adding a New REST Controller

If the controller extends `AdminRestController`, add 8 path entries following the pattern
of existing REST controllers:
- `GET /rest/{name}` — read (list)
- `GET /rest/{name}/{id}` — read (single)
- `POST /rest/{name}` — create
- `PUT /rest/{name}` — update
- `DELETE /rest/{name}/{id}` — delete
- `POST /rest/{name}/bulkUpdate` — bulk update
- `POST /rest/{name}/bulkDelete` — bulk delete
- `GET /rest/{name}/lookupData` — lookup data

### Adding a New Domain Model

Add the schema to `components.schemas` in the spec, following existing examples
(e.g., `AppConfig`, `Monitor`, `Preference`).

### Conventions

- **Tags**: Group endpoints by feature area. Use `Admin: ` prefix for admin endpoints.
- **Operation IDs**: `{controllerUrlName}.{actionName}` (e.g., `configAdmin.read`)
- **Roles**: Document required roles in endpoint descriptions, not in the schema itself
  (since roles are enforced server-side and may vary by deployment).
- **Response schemas**: Use `$ref` to domain model schemas where possible. Use inline
  `type: object` for dynamic/app-specific response shapes.

## Endpoint Coverage Summary

| Category | Controllers | Endpoints |
|----------|------------|-----------|
| Core (XhController) | 1 | 27 |
| Views (XhViewController) | 1 | 7 |
| Proxy | 1 | 1 |
| Admin REST CRUD | 6 | 48 |
| Admin Non-REST | 10 | 25 |
| Admin Cluster | 7 | 16 |
| **Total** | **26** | **122** |
