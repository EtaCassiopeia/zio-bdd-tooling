import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as path from 'path';
import { LanguageClient } from 'vscode-languageclient/node';

// All step-block keywords that introduce a named scenario.
const SCENARIO_RE = /^\s*(Scenario Outline:|Scenario Template:|Scenario:|Example:)\s*(.*)$/;
const FEATURE_RE  = /^\s*Feature:\s*(.*)$/;

/**
 * Registers zio-bdd scenarios as VS Code Test Explorer items.
 *
 * Each Feature becomes a parent TestItem; each Scenario / Scenario Outline /
 * Example becomes a child.  Clicking ▶ in the Test Explorer runs the item via
 * `sbt testOnly` and captures stdout/stderr into the test run output panel,
 * then marks the item passed or failed based on the sbt exit code.
 */
export function registerTestController(
  context: vscode.ExtensionContext,
  client: LanguageClient,
): void {
  const controller = vscode.tests.createTestController('zio-bdd', 'zio-bdd Scenarios');
  context.subscriptions.push(controller);

  controller.createRunProfile(
    'Run',
    vscode.TestRunProfileKind.Run,
    (request, token) => runTests(request, token, controller, client),
    true,
  );

  scanWorkspace(controller);

  const watcher = vscode.workspace.createFileSystemWatcher('**/*.feature');
  watcher.onDidCreate(uri => scanFeatureFile(uri, controller));
  watcher.onDidChange(uri => scanFeatureFile(uri, controller));
  watcher.onDidDelete(uri => {
    controller.items.forEach(item => {
      if (item.uri?.toString() === uri.toString()) controller.items.delete(item.id);
    });
  });
  context.subscriptions.push(watcher);
}

async function scanWorkspace(controller: vscode.TestController): Promise<void> {
  const files = await vscode.workspace.findFiles('**/*.feature', '**/node_modules/**');
  await Promise.all(files.map(uri => scanFeatureFile(uri, controller)));
}

async function scanFeatureFile(
  uri: vscode.Uri,
  controller: vscode.TestController,
): Promise<void> {
  const doc    = await vscode.workspace.openTextDocument(uri);
  const lines  = doc.getText().split('\n');

  const featureName =
    lines.map(l => FEATURE_RE.exec(l)).find(Boolean)?.[1]?.trim()
    ?? path.basename(uri.fsPath, '.feature');

  const featureItem = controller.createTestItem(uri.toString(), featureName, uri);
  featureItem.canResolveChildren = true;

  lines.forEach((line, index) => {
    const m = SCENARIO_RE.exec(line);
    if (!m) return;
    const scenarioName = m[2].trim();
    const id   = `${uri.toString()}::${scenarioName}`;
    const item = controller.createTestItem(id, scenarioName, uri);
    item.range = new vscode.Range(index, 0, index, line.length);
    featureItem.children.add(item);
  });

  controller.items.add(featureItem);
}

async function runTests(
  request: vscode.TestRunRequest,
  token: vscode.CancellationToken,
  controller: vscode.TestController,
  client: LanguageClient,
): Promise<void> {
  const run  = controller.createTestRun(request);
  const cwd  = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;

  const items = request.include ?? [...collectAll(controller.items)];
  const excluded = new Set(request.exclude?.map(i => i.id) ?? []);

  for (const item of items) {
    if (token.isCancellationRequested) break;
    if (excluded.has(item.id)) continue;
    run.started(item);
    await executeItem(item, client, cwd, run, token);
  }

  run.end();
}

async function executeItem(
  item: vscode.TestItem,
  client: LanguageClient,
  cwd: string | undefined,
  run: vscode.TestRun,
  token: vscode.CancellationToken,
): Promise<void> {
  // Build the command on the LSP server (the same path the CodeLens uses): it
  // knows the matching suite selector, adds --feature-file, and quotes for sbt.
  // Building it here can't see the workspace index, so it would mis-target.
  let cmd: string;
  try {
    const featureUri   = item.uri?.toString();
    const scenarioName = item.id.includes('::') ? item.label : null;
    cmd = await client.sendRequest<string>('zio-bdd/buildRunCommand', { featureUri, scenarioName });
  } catch (err) {
    run.failed(item, new vscode.TestMessage(`Could not build run command from the zio-bdd LSP: ${err}`));
    return;
  }

  return new Promise(resolve => {
    run.appendOutput(`▶ ${cmd}\r\n`, undefined, item);

    const proc = cp.spawn(cmd, { shell: true, cwd, env: process.env });

    proc.stdout.on('data', (chunk: Buffer) =>
      run.appendOutput(normaliseNewlines(chunk.toString()), undefined, item));
    proc.stderr.on('data', (chunk: Buffer) =>
      run.appendOutput(normaliseNewlines(chunk.toString()), undefined, item));

    proc.on('close', code => {
      if (code === 0) {
        run.passed(item);
      } else {
        run.failed(item, new vscode.TestMessage(
          `sbt exited with code ${code ?? '?'}. See output for details.`
        ));
      }
      resolve();
    });

    proc.on('error', err => {
      run.failed(item, new vscode.TestMessage(`Failed to start sbt: ${err.message}`));
      resolve();
    });

    token.onCancellationRequested(() => {
      proc.kill();
      run.skipped(item);
      resolve();
    });
  });
}

function normaliseNewlines(s: string): string {
  return s.replace(/\r?\n/g, '\r\n');
}

function collectAll(items: vscode.TestItemCollection): vscode.TestItem[] {
  const result: vscode.TestItem[] = [];
  items.forEach(item => {
    result.push(item);
    result.push(...collectAll(item.children));
  });
  return result;
}
