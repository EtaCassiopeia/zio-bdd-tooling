import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';

export function registerCommands(
  context: vscode.ExtensionContext,
  client: LanguageClient | undefined,
  output: vscode.OutputChannel,
  statusBar: vscode.StatusBarItem,
): void {
  // No "generate step registry" command here: `sbt generateStepRegistry` is defined in
  // zio-bdd's own build (project/*.scala auto-plugins), not available to a project that just
  // depends on the published zio-bdd library — see EtaCassiopeia/zio-bdd#104. This extension's
  // own go-to-definition/hover/completion/diagnostics don't need that intermediate JSON anyway;
  // they come straight from the LSP's live source scan.

  // Restart LSP server
  context.subscriptions.push(
    vscode.commands.registerCommand('zio-bdd.restartServer', async () => {
      if (client) {
        statusBar.text = '$(loading~spin) zio-bdd: restarting…';
        await client.restart();
        statusBar.text = '$(check) zio-bdd: ready';
        output.appendLine('LSP server restarted.');
      }
    })
  );

  // Show output channel
  context.subscriptions.push(
    vscode.commands.registerCommand('zio-bdd.showOutput', () => {
      output.show();
    })
  );
}
