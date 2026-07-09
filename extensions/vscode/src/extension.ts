import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as cp from 'child_process';
import {
  InlayHintRequest,
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
} from 'vscode-languageclient/node';
import { registerCommands } from './commands';
import { registerTestController } from './testController';
import { ScenarioExplorerProvider } from './sidebarProvider';

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel;
let statusBarItem: vscode.StatusBarItem;

/** Whether step data-flow inlay hints (#60) are enabled; off by default. */
function stepDataFlowHintsEnabled(): boolean {
  return vscode.workspace.getConfiguration('zio-bdd').get<boolean>('stepDataFlowHints.enabled', false);
}

/** Ask VS Code to re-query inlay hints for the visible editors (used when the toggle flips).
 *  No-op until the server has registered the inlay-hints provider. */
function refreshInlayHints(): void {
  if (!client) return;
  const feature = client.getFeature(InlayHintRequest.method);
  for (const editor of vscode.window.visibleTextEditors) {
    feature.getProvider(editor.document)?.onDidChangeInlayHints.fire();
  }
}

export function activate(context: vscode.ExtensionContext): void {
  outputChannel = vscode.window.createOutputChannel('zio-bdd');
  context.subscriptions.push(outputChannel);

  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
  statusBarItem.text = '$(loading~spin) zio-bdd: starting…';
  statusBarItem.show();
  context.subscriptions.push(statusBarItem);

  const launch = resolveLspLaunch(context);
  if (!launch) {
    vscode.window.showErrorMessage(
      'zio-bdd: LSP server not found. Build it with `sbt lsp/assembly` (produces zio-bdd-lsp.jar) ' +
      'or set zio-bdd.lspBinaryPath in settings.'
    );
    statusBarItem.text = '$(warning) zio-bdd: LSP not found';
    return;
  }

  const conf     = vscode.workspace.getConfiguration('zio-bdd');
  const logLevel = conf.get<string>('logLevel') ?? 'info';

  const serverOptions: ServerOptions = {
    command: launch.command,
    args: launch.args,
    transport: TransportKind.stdio,
    options: {
      env: { ...process.env, ZIO_LOG_LEVEL: logLevel.toUpperCase() },
    },
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: 'file', language: 'gherkin' },
      { scheme: 'file', pattern: '**/*.feature' },
      // 'language: scala' only matches when Metals is installed and has registered the
      // scala language ID. The pattern fallback ensures the LSP attaches to .scala files
      // even in vanilla VS Code without any Scala extension.
      { scheme: 'file', language: 'scala' },
      { scheme: 'file', pattern: '**/*.scala' },
    ],
    outputChannel,
    synchronize: {
      fileEvents: vscode.workspace.createFileSystemWatcher('**/*.{feature,scala}'),
    },
    middleware: {
      // Step data-flow inlay hints (#60) are opt-in. The server always serves them; the
      // client decides whether to show them, gated on zio-bdd.stepDataFlowHints.enabled.
      // (VS Code already suppresses all inlay hints when editor.inlayHints.enabled is off,
      // so this middleware isn't even reached in that case.)
      provideInlayHints: (document, viewPort, token, next) => {
        if (!stepDataFlowHintsEnabled()) return [];
        return next(document, viewPort, token);
      },
    },
  };

  client = new LanguageClient(
    'zio-bdd',
    'zio-bdd Language Server',
    serverOptions,
    clientOptions
  );

  client.start().then(() => {
    statusBarItem.text = '$(check) zio-bdd: ready';
    outputChannel.appendLine('zio-bdd LSP server started.');
    sidebar.setLspClient(client!);  // client is defined here; start() resolves after successful connection
  }).catch((err: Error) => {
    statusBarItem.text = '$(error) zio-bdd: error';
    outputChannel.appendLine(`zio-bdd LSP failed to start: ${err.message}`);
  });

  // Toggle step data-flow inlay hints live: flipping the setting fires the inlay-hints
  // provider's change event so VS Code re-queries and the middleware re-reads the setting,
  // showing/hiding hints without a window reload.
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('zio-bdd.stepDataFlowHints.enabled')) refreshInlayHints();
    })
  );

  // Handler for "▶ Run" code-lens buttons emitted by the LSP server.
  context.subscriptions.push(
    vscode.commands.registerCommand('zio-bdd.runCommand', (sbtCmd: string) => {
      const terminal = vscode.window.createTerminal('zio-bdd');
      terminal.show();
      terminal.sendText(sbtCmd);
    })
  );

  // Handler for "N usages" code-lens buttons on Scala step definitions.
  // Calls our LSP directly (not vscode.executeReferenceProvider, which also
  // invokes Metals and mixes in Scala-symbol references for .scala files).
  context.subscriptions.push(
    vscode.commands.registerCommand(
      'zio-bdd.findStepUsages',
      async (uriStr: string, line: number, character: number) => {
        if (!client) return;

        interface LspLoc {
          uri: string;
          range: { start: { line: number; character: number } };
        }
        const lspLocs = await client.sendRequest<LspLoc[] | null>('textDocument/references', {
          textDocument: { uri: uriStr },
          position: { line, character },
          context: { includeDeclaration: false },
        });

        if (!lspLocs?.length) {
          vscode.window.showInformationMessage('No usages found for this step definition');
          return;
        }

        const open = async (loc: LspLoc) => {
          await vscode.window.showTextDocument(vscode.Uri.parse(loc.uri), {
            selection: new vscode.Range(
              new vscode.Position(loc.range.start.line, 0),
              new vscode.Position(loc.range.start.line, 0),
            ),
            preserveFocus: false,
          });
        };

        if (lspLocs.length === 1) {
          await open(lspLocs[0]);
          return;
        }

        // Multiple usages — two ways to navigate:
        //
        // 1. QuickPick: hover/arrow key previews the file (preview tab, focus stays
        //    in QuickPick); clicking an item or pressing Enter opens it permanently.
        //    This is the "double-click" feel: first interaction previews, second confirms.
        //
        // 2. Peek panel: the QuickPick also passes locations to
        //    editor.action.showReferences so the inline peek stays open behind it.
        //    Double-clicking any entry in the peek navigates directly.
        const vsLocs = lspLocs.map(loc =>
          new vscode.Location(
            vscode.Uri.parse(loc.uri),
            new vscode.Range(
              new vscode.Position(loc.range.start.line, 0),
              new vscode.Position(loc.range.start.line, 1000), // highlight line in peek preview
            )
          )
        );

        // Show peek panel first so it's visible behind the QuickPick
        void vscode.commands.executeCommand(
          'editor.action.showReferences',
          vscode.Uri.parse(uriStr),
          new vscode.Position(line, character),
          vsLocs,
        );

        type Item = vscode.QuickPickItem & { loc: LspLoc };
        const items: Item[] = lspLocs.map(loc => ({
          label:              `$(file) ${path.basename(loc.uri.replace(/^file:\/\//, ''))}`,
          description:        path.dirname(loc.uri.replace(/^file:\/\//, '')),
          detail:             `Line ${loc.range.start.line + 1}`,
          loc,
        }));

        const qp = vscode.window.createQuickPick<Item>();
        qp.items              = items;
        qp.placeholder        = `${lspLocs.length} usages — hover to preview · Enter or click to open`;
        qp.matchOnDescription = true;
        qp.matchOnDetail      = true;

        qp.onDidChangeActive(active => {
          if (!active[0]) return;
          vscode.window.showTextDocument(vscode.Uri.parse(active[0].loc.uri), {
            selection: new vscode.Range(
              new vscode.Position(active[0].loc.range.start.line, 0),
              new vscode.Position(active[0].loc.range.start.line, 0),
            ),
            preview:       true,  // italic tab title — discarded on next open
            preserveFocus: true,  // keep focus in QuickPick
          });
        });

        qp.onDidAccept(async () => {
          const [chosen] = qp.activeItems;
          qp.hide();
          if (chosen) await open(chosen.loc);
        });

        qp.show();
      }
    )
  );

  registerCommands(context, client, outputChannel, statusBarItem);
  registerTestController(context, client);

  // Scenario Explorer sidebar
  const sidebar = new ScenarioExplorerProvider(context.extensionUri);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ScenarioExplorerProvider.viewId, sidebar),
  );
  context.subscriptions.push(
    vscode.commands.registerCommand('zio-bdd.refreshSidebar', () => sidebar.refresh()),
  );
  const featureWatcher = vscode.workspace.createFileSystemWatcher('**/*.feature');
  const notInBuildDir = (uri: vscode.Uri) =>
    !/[/\\](target|node_modules|\.bloop|\.metals|\.bsp|out)[/\\]/.test(uri.fsPath);
  featureWatcher.onDidCreate(uri => { if (notInBuildDir(uri)) sidebar.refresh(); });
  featureWatcher.onDidChange(uri => { if (notInBuildDir(uri)) sidebar.refresh(); });
  featureWatcher.onDidDelete(uri => { if (notInBuildDir(uri)) sidebar.refresh(); });
  context.subscriptions.push(featureWatcher);

  context.subscriptions.push({ dispose: () => client?.stop() });
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}

interface Launch {
  command: string;
  args: string[];
}

/**
 * Resolve how to start the LSP server.
 *
 * Preference order:
 *   1. `zio-bdd.lspBinaryPath` setting (any absolute path, native binary or .jar)
 *   2. Bundled `bin/zio-bdd-lsp[.exe]` native binary next to the extension
 *   3. Bundled `bin/zio-bdd-lsp.jar` next to the extension
 *   4. `zio-bdd-lsp[.exe]` on PATH
 *
 * A GraalVM native binary (produced by `sbt lsp/nativeImage`) is preferred over
 * the fat jar where available — faster startup, no JVM warm-up.
 */
function resolveLspLaunch(context: vscode.ExtensionContext): Launch | undefined {
  const configured = vscode.workspace.getConfiguration('zio-bdd').get<string>('lspBinaryPath');
  if (configured && fs.existsSync(configured)) return toLaunch(configured);

  const bundledBinary = path.join(context.extensionPath, 'bin', lspBinaryName());
  if (fs.existsSync(bundledBinary)) return toLaunch(bundledBinary);

  const bundledJar = path.join(context.extensionPath, 'bin', 'zio-bdd-lsp.jar');
  if (fs.existsSync(bundledJar)) return toLaunch(bundledJar);

  const onPath = findOnPath(lspBinaryName());
  if (onPath) return toLaunch(onPath);

  return undefined;
}

function toLaunch(resolvedPath: string): Launch {
  return resolvedPath.endsWith('.jar')
    ? { command: javaExecutable(), args: ['-jar', resolvedPath] }
    : { command: resolvedPath, args: [] };
}

function lspBinaryName(): string {
  return process.platform === 'win32' ? 'zio-bdd-lsp.exe' : 'zio-bdd-lsp';
}

function javaExecutable(): string {
  const javaHome = process.env['JAVA_HOME'];
  if (javaHome) {
    const bin = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(bin)) return bin;
  }
  return 'java';
}

function findOnPath(name: string): string | undefined {
  // `which` on unix, `where` on Windows — both return a newline-separated list;
  // we take the first non-empty result.
  const cmd = process.platform === 'win32' ? `where ${name}` : `which ${name}`;
  try {
    const result = cp.execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    return result.split(/\r?\n/)[0] || undefined;
  } catch {
    return undefined;
  }
}
