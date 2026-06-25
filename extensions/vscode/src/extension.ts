import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as cp from 'child_process';
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

  // Handler for "▶ Run" code-lens buttons emitted by the LSP server.
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
