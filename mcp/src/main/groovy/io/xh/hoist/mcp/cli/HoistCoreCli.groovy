package io.xh.hoist.mcp.cli

import io.xh.hoist.mcp.util.McpLog
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Picocli root command for the hoist-core CLI. Dispatched from
 * {@code HoistCoreMcpServer.main(args)} when the first arg is {@code cli}.
 *
 * Subcommand tree:
 *   docs    -- documentation search/list/read/conventions/index/ping
 *   symbols -- Groovy/Java symbol search/symbol/members
 *
 * Wrapper scripts produced by the app-side install task (see mcp/README.md)
 * resolve to {@code java -jar JAR cli docs ...} and {@code ... cli symbols ...}.
 */
@Command(
    name = 'hoist-core',
    mixinStandardHelpOptions = true,
    version = 'hoist-core CLI',
    description = 'Search and inspect hoist-core documentation and Groovy/Java symbols.',
    subcommands = [DocsCli, SymbolsCli]
)
class HoistCoreCli implements Runnable {

    @Option(
        names = ['--source'],
        description = 'Content source: bundled (default), local, or github:REF',
        scope = CommandLine.ScopeType.INHERIT
    )
    String source = 'bundled'

    @Option(
        names = ['--root'],
        description = 'Repo root path when --source local',
        scope = CommandLine.ScopeType.INHERIT
    )
    String root

    @Option(
        names = ['--verbose'],
        description = 'Show internal info-level logs on stderr (otherwise suppressed for CLI use).',
        scope = CommandLine.ScopeType.INHERIT
    )
    boolean verbose

    static int run(String[] args) {
        // Quiet info-level logs by default; respect existing HOIST_MCP_DEBUG override.
        if (System.getenv('HOIST_MCP_DEBUG') != '1') {
            McpLog.setQuiet(true)
        }
        def cli = new HoistCoreCli()
        def cmd = new CommandLine(cli)
        // Re-enable info logs after parsing if --verbose was passed.
        cmd.executionStrategy = { CommandLine.ParseResult pr ->
            if (cli.verbose) McpLog.setQuiet(false)
            new CommandLine.RunLast().execute(pr)
        }
        return cmd.execute(args)
    }

    @Override
    void run() {
        // No subcommand specified — print usage to stderr and exit non-zero.
        CommandLine.usage(this, System.err)
    }

    /** Resolve the CliContext from inherited --source / --root options. */
    static CliContext contextFrom(CommandLine.Model.CommandSpec spec) {
        def root = spec
        while (root.parent() != null) root = root.parent()
        def cli = root.userObject() as HoistCoreCli
        return new CliContext(cli.source, cli.root)
    }
}
