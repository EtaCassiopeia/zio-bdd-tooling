import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
} from 'vscode-languageclient/node';
import { registerCommands } from './commands';
import { registerTestController } from './testController';

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel;
let statusBarItem: vscode.StatusBarItem;

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

  const serverOptions: ServerOptions = {
    command: launch.command,
    args: launch.args,
    transport: TransportKind.stdio,
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: 'file', language: 'gherkin' },
      { scheme: 'file', pattern: '**/*.feature' },
      { scheme: 'file', language: 'scala' },
    ],
    outputChannel,
    synchronize: {
      fileEvents: vscode.workspace.createFileSystemWatcher('**/*.{feature,scala}'),
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
  }).catch((err: Error) => {
    statusBarItem.text = '$(error) zio-bdd: error';
    outputChannel.appendLine(`zio-bdd LSP failed to start: ${err.message}`);
  });

  // Register code lens command handler
  context.subscriptions.push(
    vscode.commands.registerCommand('zio-bdd.runCommand', (sbtCmd: string) => {
      const terminal = vscode.window.createTerminal('zio-bdd');
      terminal.show();
      terminal.sendText(sbtCmd);
    })
  );

  registerCommands(context, client, outputChannel, statusBarItem);
  registerTestController(context, client);

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
 * GraalVM native-image distribution (a standalone `zio-bdd-lsp` binary) is not
 * built yet (deferred — see issue #93's M3 phasing), so today this almost
 * always resolves to `java -jar .../zio-bdd-lsp.jar`. The native-binary paths
 * are checked first so this keeps working without changes once M3 ships.
 */
function resolveLspLaunch(context: vscode.ExtensionContext): Launch | undefined {
  // 1. User-configured path — a native binary or a .jar, either is fine.
  const configured = vscode.workspace.getConfiguration('zio-bdd').get<string>('lspBinaryPath');
  if (configured && fs.existsSync(configured)) return toLaunch(configured);

  // 2. Bundled alongside the extension: native binary first, then fat jar.
  const bundledBinary = path.join(context.extensionPath, 'bin', lspBinaryName());
  if (fs.existsSync(bundledBinary)) return toLaunch(bundledBinary);
  const bundledJar = path.join(context.extensionPath, 'bin', 'zio-bdd-lsp.jar');
  if (fs.existsSync(bundledJar)) return toLaunch(bundledJar);

  // 3. Native binary on PATH.
  const onPath = findOnPath('zio-bdd-lsp');
  if (onPath) return toLaunch(onPath);

  return undefined;
}

function toLaunch(resolvedPath: string): Launch {
  return resolvedPath.endsWith('.jar')
    ? { command: 'java', args: ['-jar', resolvedPath] }
    : { command: resolvedPath, args: [] };
}

function lspBinaryName(): string {
  return process.platform === 'win32' ? 'zio-bdd-lsp.exe' : 'zio-bdd-lsp';
}

function findOnPath(name: string): string | undefined {
  const { execSync } = require('child_process');
  try {
    const result = execSync(`which ${name}`, { encoding: 'utf8' }).trim();
    return result || undefined;
  } catch {
    return undefined;
  }
}
