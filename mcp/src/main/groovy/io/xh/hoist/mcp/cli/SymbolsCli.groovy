package io.xh.hoist.mcp.cli

import groovy.json.JsonOutput
import io.xh.hoist.mcp.formatters.GroovyFormatter
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec

import java.util.concurrent.Callable

/**
 * Symbol-exploration CLI subcommand tree, mirroring hoist-react's
 * {@code hoist-ts} command. All subcommands delegate to {@link GroovyFormatter},
 * producing identical output to the corresponding MCP tools.
 *
 * Subcommands:
 *   search   -- name search across classes/interfaces/traits/enums + indexed members
 *   symbol   -- detailed type info for a single symbol
 *   members  -- list all properties and methods of a class/interface
 */
@Command(
    name = 'symbols',
    description = 'Search and inspect hoist-core Groovy/Java symbols.',
    mixinStandardHelpOptions = true,
    subcommands = [
        SymbolsCli.Search,
        SymbolsCli.Symbol,
        SymbolsCli.Members
    ]
)
class SymbolsCli implements Runnable {

    @Spec CommandSpec spec

    @Override
    void run() {
        picocli.CommandLine.usage(this, System.err)
    }

    private static final List<String> VALID_KINDS = ['class', 'interface', 'trait', 'enum']

    //------------------------------------------------------------------
    // search
    //------------------------------------------------------------------
    @Command(name = 'search', description = 'Search Groovy/Java symbols and members of key framework classes.', mixinStandardHelpOptions = true)
    static class Search implements Callable<Integer> {
        @ParentCommand SymbolsCli parent
        @Parameters(paramLabel = '<query>', description = 'Symbol or member name (e.g. "BaseService", "createTimer")') String query
        @Option(names = ['-k', '--kind'], description = 'Filter by kind: class, interface, trait, enum') String kind
        @Option(names = ['-l', '--limit'], description = 'Max symbol results, 1-50. Default: 20') int limit = 20
        @Option(names = ['--json'], description = 'Emit JSON matching the MCP outputSchema.') boolean json

        @Override
        Integer call() {
            if (limit < 1 || limit > 50) {
                System.err.println("Invalid --limit: ${limit}. Must be 1-50.")
                return 1
            }
            if (kind && !VALID_KINDS.contains(kind)) {
                System.err.println("Invalid --kind: \"${kind}\". Valid kinds: ${VALID_KINDS.join(', ')}.")
                return 1
            }
            def ctx = HoistCoreCli.contextFrom(parent.spec)
            def reg = ctx.groovyRegistry
            def symbolResults = reg.searchSymbols(query, kind, limit)
            int memberLimit = symbolResults.empty ? limit : 15
            def memberResults = reg.searchMembers(query, memberLimit)
            if (json) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(
                    GroovyFormatter.searchSymbolsAsMap(query, symbolResults, memberResults)
                ))
                return 0
            }
            println GroovyFormatter.formatSearchSymbols(query, symbolResults, memberResults)
            return 0
        }
    }

    //------------------------------------------------------------------
    // symbol
    //------------------------------------------------------------------
    @Command(name = 'symbol', aliases = ['get'], description = 'Get detailed type info for a specific symbol.', mixinStandardHelpOptions = true)
    static class Symbol implements Callable<Integer> {
        @ParentCommand SymbolsCli parent
        @Parameters(paramLabel = '<name>', description = 'Exact symbol name (e.g. "BaseService")') String name
        @Option(names = ['-f', '--file'], description = 'Source file path to disambiguate duplicate names') String file
        @Option(names = ['--json'], description = 'Emit JSON matching the MCP outputSchema.') boolean json

        @Override
        Integer call() {
            def ctx = HoistCoreCli.contextFrom(parent.spec)
            def reg = ctx.groovyRegistry
            def detail = reg.getSymbolDetail(name, file)
            // Fallback: if no class/interface match, check the indexed-member surface.
            // `get-symbol createCache` → method on BaseService instead of "not found".
            def memberMatches = detail ? [] : reg.findMembersByName(name)
            if (json) {
                if (detail) {
                    println JsonOutput.prettyPrint(JsonOutput.toJson(GroovyFormatter.getSymbolAsMap(name, detail)))
                } else {
                    println JsonOutput.prettyPrint(JsonOutput.toJson(GroovyFormatter.getMemberAsSymbolAsMap(name, memberMatches)))
                }
                return 0
            }
            if (!detail && !memberMatches) {
                System.err.println(GroovyFormatter.formatSymbolNotFound(name) + ' Use the `search` subcommand to find available symbols.')
                return 1
            }
            if (detail) {
                println GroovyFormatter.formatSymbolDetail(detail)
            } else {
                println GroovyFormatter.formatMemberAsSymbol(name, memberMatches)
            }
            return 0
        }
    }

    //------------------------------------------------------------------
    // members
    //------------------------------------------------------------------
    @Command(name = 'members', aliases = ['list'], description = 'List all properties and methods of a class or interface.', mixinStandardHelpOptions = true)
    static class Members implements Callable<Integer> {
        @ParentCommand SymbolsCli parent
        @Parameters(paramLabel = '<name>', description = 'Class or interface name') String name
        @Option(names = ['-f', '--file'], description = 'Source file path to disambiguate duplicate names') String file
        @Option(names = ['--json'], description = 'Emit JSON matching the MCP outputSchema.') boolean json

        @Override
        Integer call() {
            def ctx = HoistCoreCli.contextFrom(parent.spec)
            def members = ctx.groovyRegistry.getMembers(name, file)
            if (json) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(GroovyFormatter.getMembersAsMap(name, members)))
                return 0
            }
            if (members == null) {
                System.err.println(GroovyFormatter.formatMembersNotFound(name) + ' Use the `search` subcommand to find the correct name.')
                return 1
            }
            println GroovyFormatter.formatMembers(name, members)
            return 0
        }
    }
}
