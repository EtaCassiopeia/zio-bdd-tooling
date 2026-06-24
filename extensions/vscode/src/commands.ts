import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';

export function registerCommands(
  context: vscode.ExtensionContext,
  client: LanguageClient | undefined,
  output: vscode.OutputChannel,
  statusBar: vscode.StatusBarItem,
): void {

  // Generate step registry
  context.subscriptions.push(
    vscode.commands.registerCommand('zio-bdd.generateRegistry', async () => {
      const sbt = vscode.workspace.getConfiguration('zio-bdd').get<string>('sbtCommand') ?? 'sbt';
      const terminal = vscode.window.createTerminal('zio-bdd: generate registry');
      terminal.show();
      terminal.sendText(`${sbt} generateStepRegistry`);
      statusBar.text = '$(sync~spin) zio-bdd: regenerating…';
      // Reset status bar after a short delay
      setTimeout(() => { statusBar.text = '$(check) zio-bdd: ready'; }, 10_000);
    })
  );

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
