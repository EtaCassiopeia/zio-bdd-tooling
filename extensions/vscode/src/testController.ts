import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';
import * as path from 'path';

/**
 * Registers zio-bdd scenarios as VSCode Test Explorer items.
 * Each Scenario: line in a .feature file becomes a TestItem.
 * Clicking ▶ in the Test Explorer runs it via sbt testOnly.
 */
export function registerTestController(
  context: vscode.ExtensionContext,
  client: LanguageClient | undefined,
): void {
  const controller = vscode.tests.createTestController('zio-bdd', 'zio-bdd Scenarios');
  context.subscriptions.push(controller);

  controller.createRunProfile(
    'Run',
    vscode.TestRunProfileKind.Run,
    (request, token) => runTests(request, token, controller),
    true,
  );

  // Scan all feature files when the workspace opens
  scanWorkspace(controller);

  // Re-scan when feature files change
  const watcher = vscode.workspace.createFileSystemWatcher('**/*.feature');
  watcher.onDidCreate(uri => scanFeatureFile(uri, controller));
  watcher.onDidChange(uri => scanFeatureFile(uri, controller));
  watcher.onDidDelete(uri => {
    controller.items.forEach(item => {
      if (item.uri?.toString() === uri.toString()) {
        controller.items.delete(item.id);
      }
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
  const content = await vscode.workspace.openTextDocument(uri).then(d => d.getText());
  const lines = content.split('\n');

  let featureItem: vscode.TestItem | undefined;
  const featureName = lines
    .find(l => l.trim().startsWith('Feature:'))
    ?.replace(/^\s*Feature:\s*/, '') ?? path.basename(uri.fsPath, '.feature');

  featureItem = controller.createTestItem(uri.toString(), featureName, uri);
  featureItem.canResolveChildren = true;

  lines.forEach((line, index) => {
    const stripped = line.trim();
    if (stripped.startsWith('Scenario:') || stripped.startsWith('Example:')) {
      const scenarioName = stripped.replace(/^\s*(Scenario|Example):\s*/, '');
      const id = `${uri.toString()}::${scenarioName}`;
      const item = controller.createTestItem(id, scenarioName, uri);
      item.range = new vscode.Range(index, 0, index, line.length);
      featureItem!.children.add(item);
    }
  });

  controller.items.add(featureItem);
}

async function runTests(
  request: vscode.TestRunRequest,
  token: vscode.CancellationToken,
  controller: vscode.TestController,
): Promise<void> {
  const run = controller.createTestRun(request);
  const sbt = vscode.workspace.getConfiguration('zio-bdd').get<string>('sbtCommand') ?? 'sbt';

  const items = request.include ?? [...collectAll(controller.items)];

  for (const item of items) {
    if (token.isCancellationRequested) break;
    run.started(item);

    const scenarioName = item.label;
    const sbtCmd = `${sbt} "testOnly * -- --scenario-name '${scenarioName.replace(/'/g, `'"'"'`)}'"`;

    const terminal = vscode.window.createTerminal(`zio-bdd: ${scenarioName}`);
    terminal.show();
    terminal.sendText(sbtCmd);

    // We can't easily get the exit code from a terminal; mark as enqueued.
    run.enqueued(item);
  }

  run.end();
}

function collectAll(items: vscode.TestItemCollection): vscode.TestItem[] {
  const result: vscode.TestItem[] = [];
  items.forEach(item => {
    result.push(item);
    result.push(...collectAll(item.children));
  });
  return result;
}
