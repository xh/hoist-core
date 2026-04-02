package io.xh.hoist.mcp.resources

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.Resource
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import io.xh.hoist.mcp.data.DocRegistry
import io.xh.hoist.mcp.util.McpLog

/**
 * Creates documentation resource specifications for the MCP server.
 *
 * Registers each document in the DocRegistry as an MCP resource, allowing
 * clients to read full document content via `hoist-core://docs/{docId}` URIs.
 */
class DocResources {

    static List<SyncResourceSpecification> create(DocRegistry registry) {
        def specs = registry.entries.collect { entry ->
            def resource = Resource.builder()
                .uri("hoist-core://docs/${entry.id}")
                .name(entry.title)
                .title('Hoist Core Documentation')
                .description(entry.description)
                .mimeType('text/markdown')
                .build()

            new SyncResourceSpecification(resource, { exchange, request ->
                def content = registry.loadContent(entry.id)
                if (!content) {
                    throw new RuntimeException("Document not found: ${entry.id}")
                }
                new ReadResourceResult([
                    new TextResourceContents(request.uri(), 'text/markdown', content)
                ])
            })
        }

        McpLog.info("Registered ${specs.size()} doc resources via hoist-core://docs/{docId}")
        return specs
    }
}
