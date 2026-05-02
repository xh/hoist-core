package io.xh.hoist.mcp.cli

import groovy.json.JsonOutput
import io.xh.hoist.mcp.formatters.DocFormatter
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec

import java.util.concurrent.Callable

/**
 * Documentation CLI subcommand tree, mirroring hoist-react's {@code hoist-docs}
 * command. All subcommands delegate to {@link DocFormatter}, producing identical
 * output to the corresponding MCP tools.
 *
 * Subcommands:
 *   search       -- keyword search across all docs
 *   list         -- list available docs by category
 *   read         -- print a doc by id
 *   conventions  -- shortcut for read docs/coding-conventions.md
 *   index        -- shortcut for read docs/README.md
 *   ping         -- responsiveness check
 */
@Command(
    name = 'docs',
    description = 'Search, list, and read hoist-core documentation.',
    mixinStandardHelpOptions = true,
    subcommands = [
        DocsCli.Search,
        DocsCli.ListCmd,
        DocsCli.Read,
        DocsCli.Conventions,
        DocsCli.Index,
        DocsCli.Ping
    ]
)
class DocsCli implements Runnable {

    @Spec CommandSpec spec

    @Override
    void run() {
        picocli.CommandLine.usage(this, System.err)
    }

    private static final String CONVENTIONS_ID = 'docs/coding-conventions.md'
    private static final String INDEX_ID = 'docs/README.md'

    //------------------------------------------------------------------
    // search
    //------------------------------------------------------------------
    @Command(name = 'search', description = 'Search across all hoist-core documentation by keyword.', mixinStandardHelpOptions = true)
    static class Search implements Callable<Integer> {
        @ParentCommand DocsCli parent
        @Parameters(paramLabel = '<query>', description = 'Search keywords (e.g. "BaseService lifecycle")') String query
        @Option(names = ['-c', '--category'], description = 'Filter by category. Default: all') String category = 'all'
        @Option(names = ['-l', '--limit'], description = 'Max results, 1-20. Default: 10') int limit = 10
        @Option(names = ['--json'], description = 'Emit JSON matching the MCP outputSchema.') boolean json

        @Override
        Integer call() {
            if (limit < 1 || limit > 20) {
                System.err.println("Invalid --limit: ${limit}. Must be 1-20.")
                return 1
            }
            def ctx = HoistCoreCli.contextFrom(parent.spec)
            def results = ctx.docRegistry.searchDocs(query, category, limit)
            if (json) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(DocFormatter.searchDocsAsMap(query, results)))
                return 0
            }
            println DocFormatter.formatSearchDocs(query, results)
            return 0
        }
    }

    //------------------------------------------------------------------
    // list
    //------------------------------------------------------------------
    @Command(name = 'list', description = 'List all available documentation, grouped by category.', mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @ParentCommand DocsCli parent
        @Option(names = ['-c', '--category'], description = 'Filter by category. Default: all') String category = 'all'
        @Option(names = ['--json'], description = 'Emit JSON matching the MCP outputSchema.') boolean json

        @Override
        Integer call() {
            def ctx = HoistCoreCli.contextFrom(parent.spec)
            def filtered = ctx.docRegistry.listDocs(category)
            if (json) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(
                    DocFormatter.listDocsAsMap(filtered, ctx.docRegistry.mcpCategories)
                ))
                return 0
            }
            println DocFormatter.formatListDocs(filtered, ctx.docRegistry.mcpCategories)
            return 0
        }
    }

    //------------------------------------------------------------------
    // read
    //------------------------------------------------------------------
    @Command(name = 'read', aliases = ['get'], description = 'Read a specific document by id.', mixinStandardHelpOptions = true)
    static class Read implements Callable<Integer> {
        @ParentCommand DocsCli parent
        @Parameters(paramLabel = '<docId>', description = 'Document id (e.g. "docs/coding-conventions.md")') String docId
        @Option(names = ['--json'], description = 'Emit JSON with metadata + content.') boolean json

        @Override
        Integer call() {
            def ctx = HoistCoreCli.contextFrom(parent.spec)
            def res = ctx.docRegistry.resolve(docId)
            if (res.ambiguous) {
                if (json) {
                    println JsonOutput.prettyPrint(JsonOutput.toJson([
                        schemaVersion: DocFormatter.SCHEMA_VERSION,
                        id: docId,
                        found: false,
                        ambiguous: true,
                        candidateIds: res.candidates*.id.sort()
                    ]))
                    return 1
                }
                System.err.println(DocFormatter.formatDocAmbiguous(docId, res.candidates))
                return 1
            }
            if (!res.found) {
                if (json) {
                    println JsonOutput.prettyPrint(JsonOutput.toJson([
                        schemaVersion: DocFormatter.SCHEMA_VERSION,
                        id: docId,
                        found: false,
                        availableIds: ctx.docRegistry.entries*.id.sort()
                    ]))
                    return 0
                }
                System.err.println(DocFormatter.formatDocNotFound(docId, ctx.docRegistry.entries))
                return 1
            }
            def entry = res.entry
            def content = ctx.docRegistry.loadContent(entry.id)
            if (content == null) {
                System.err.println("Document file not readable: \"${entry.id}\".")
                return 1
            }
            if (json) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(DocFormatter.readDocAsMap(entry, content)))
                return 0
            }
            print content
            if (!content.endsWith('\n')) println()
            return 0
        }
    }

    //------------------------------------------------------------------
    // conventions
    //------------------------------------------------------------------
    @Command(name = 'conventions', description = 'Print the hoist-core coding conventions.', mixinStandardHelpOptions = true)
    static class Conventions implements Callable<Integer> {
        @ParentCommand DocsCli parent

        @Override
        Integer call() {
            return readShortcut(parent, CONVENTIONS_ID, 'Conventions document not found in registry.')
        }
    }

    //------------------------------------------------------------------
    // index
    //------------------------------------------------------------------
    @Command(name = 'index', description = 'Print the documentation index (docs/README.md).', mixinStandardHelpOptions = true)
    static class Index implements Callable<Integer> {
        @ParentCommand DocsCli parent

        @Override
        Integer call() {
            return readShortcut(parent, INDEX_ID, 'Index document not found in registry.')
        }
    }

    //------------------------------------------------------------------
    // ping
    //------------------------------------------------------------------
    @Command(name = 'ping', description = 'Verify the CLI is wired up and responsive.', mixinStandardHelpOptions = true)
    static class Ping implements Callable<Integer> {
        @ParentCommand DocsCli parent

        @Override
        Integer call() {
            println 'hoist-core CLI is running.'
            return 0
        }
    }

    //------------------------------------------------------------------
    // helpers
    //------------------------------------------------------------------
    private static int readShortcut(DocsCli parent, String id, String missingMessage) {
        def ctx = HoistCoreCli.contextFrom(parent.spec)
        def entry = ctx.docRegistry.entries.find { it.id == id }
        if (!entry) {
            System.err.println(missingMessage)
            return 1
        }
        def content = ctx.docRegistry.loadContent(id)
        if (content == null) {
            System.err.println("Document file not readable: \"${id}\".")
            return 1
        }
        print content
        if (!content.endsWith('\n')) println()
        return 0
    }
}
