import * as vscode from 'vscode';
import * as fs from 'fs';

export interface ScenarioItem {
  name: string;
  line: number;
  tags: string[];
  isOutline: boolean;
  isIgnored: boolean;
}

export interface FeatureItem {
  uri: string;
  fsPath: string;
  name: string;
  tags: string[];
  scenarios: ScenarioItem[];
}

export class ScenarioExplorerProvider implements vscode.WebviewViewProvider {
  public static readonly viewId = 'zioBdd.scenarioExplorer';
  private _view?: vscode.WebviewView;
  private _features: FeatureItem[] = [];

  constructor(private readonly _extensionUri: vscode.Uri) {}

  public resolveWebviewView(
    webviewView: vscode.WebviewView,
    _ctx: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken,
  ): void {
    this._view = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [this._extensionUri],
    };
    webviewView.webview.html = this._html(webviewView.webview);

    webviewView.webview.onDidReceiveMessage(async (msg) => {
      switch (msg.type) {
        case 'openFile': {
          const uri = vscode.Uri.parse(msg.uri as string);
          const line = Math.max(0, (msg.line as number) - 1);
          await vscode.window.showTextDocument(uri, {
            selection: new vscode.Range(line, 0, line, 0),
            preserveFocus: false,
          });
          break;
        }
        case 'runScenario': {
          const conf = vscode.workspace.getConfiguration('zio-bdd');
          const sbt  = conf.get<string>('sbtCommand') ?? 'sbt';
          const name = msg.scenarioName as string;
          const term = vscode.window.createTerminal('zio-bdd');
          term.show();
          term.sendText(`${sbt} "testOnly * -- --scenario-name '${name.replace(/'/g, "'\\''")}' --focused"`);
          break;
        }
        case 'runFeature': {
          const conf = vscode.workspace.getConfiguration('zio-bdd');
          const sbt  = conf.get<string>('sbtCommand') ?? 'sbt';
          const fsPath = msg.fsPath as string;
          const term = vscode.window.createTerminal('zio-bdd');
          term.show();
          term.sendText(`${sbt} "testOnly * -- --feature-file '${fsPath.replace(/'/g, "'\\''")}' --focused"`);
          break;
        }
        case 'runAll': {
          const conf = vscode.workspace.getConfiguration('zio-bdd');
          const sbt  = conf.get<string>('sbtCommand') ?? 'sbt';
          const term = vscode.window.createTerminal('zio-bdd');
          term.show();
          term.sendText(`${sbt} test`);
          break;
        }
        case 'refresh': {
          await this.refresh();
          break;
        }
      }
    });

    this.refresh();
  }

  public async refresh(): Promise<void> {
    this._features = await this._scan();
    this._post({ type: 'update', features: this._features });
  }

  private _post(msg: object): void {
    this._view?.webview.postMessage(msg);
  }

  private async _scan(): Promise<FeatureItem[]> {
    const uris = await vscode.workspace.findFiles('**/*.feature', '**/node_modules/**');
    const items: FeatureItem[] = [];
    for (const uri of uris) {
      try {
        const raw = fs.readFileSync(uri.fsPath, 'utf8');
        const item = parseFeature(uri, raw);
        if (item) items.push(item);
      } catch { /* skip */ }
    }
    return items.sort((a, b) => a.name.localeCompare(b.name));
  }

  private _html(webview: vscode.Webview): string {
    const nonce = nonce32();
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style nonce="${nonce}">
* { box-sizing: border-box; margin: 0; padding: 0; }

body {
  background: #0d1117;
  color: #c9d1d9;
  font-family: var(--vscode-font-family, 'Segoe UI', sans-serif);
  font-size: 12px;
  line-height: 1.5;
}

.header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  background: #161b22;
  border-bottom: 1px solid #21262d;
}

.logo {
  color: #6366f1;
  font-size: 13px;
  margin-right: 2px;
}

.header-title {
  flex: 1;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .08em;
  text-transform: uppercase;
  color: #8b949e;
}

.icon-btn {
  background: none;
  border: none;
  color: #6e7681;
  cursor: pointer;
  padding: 3px 5px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1;
  display: flex;
  align-items: center;
}
.icon-btn:hover { background: #21262d; color: #c9d1d9; }

.run-all-btn {
  background: #1f6feb22;
  border: 1px solid #1f6feb44;
  color: #7dd3fc;
  cursor: pointer;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .04em;
  display: flex;
  align-items: center;
  gap: 4px;
}
.run-all-btn:hover { background: #1f6feb44; }

.stats {
  padding: 5px 12px;
  font-size: 11px;
  color: #6e7681;
  border-bottom: 1px solid #21262d;
  display: flex;
  gap: 10px;
}

.stat-chip {
  display: flex;
  align-items: center;
  gap: 4px;
}

.stat-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.feature-section { border-bottom: 1px solid #21262d; }

.feature-header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  cursor: pointer;
  background: #161b22;
  user-select: none;
}
.feature-header:hover { background: #1c2128; }
.feature-header:hover .feat-run { opacity: 1; }

.chevron { color: #6e7681; font-size: 9px; width: 10px; flex-shrink: 0; }

.feat-name {
  font-size: 12px;
  font-weight: 500;
  color: #e6edf3;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feat-tags { display: flex; gap: 3px; flex-shrink: 0; }

.feat-count {
  font-size: 10px;
  color: #6e7681;
  background: #21262d;
  border-radius: 10px;
  padding: 0px 6px;
  flex-shrink: 0;
}

.feat-run {
  opacity: 0;
  background: none;
  border: none;
  color: #388bfd;
  cursor: pointer;
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 3px;
  flex-shrink: 0;
  transition: opacity .12s;
}
.feat-run:hover { background: #1f6feb33; }

.scenario-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 5px 10px 5px 26px;
  cursor: pointer;
}
.scenario-row:hover { background: #1c2128; }
.scenario-row:hover .sc-run { opacity: 1; }

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-top: 4px;
  flex-shrink: 0;
}
.dot-active   { background: #238636; }
.dot-outline  { background: #1f6feb; }
.dot-property { background: #8250df; }
.dot-ignored  { background: transparent; border: 1.5px solid #4d555e; }

.sc-info { flex: 1; min-width: 0; }

.sc-name {
  font-size: 12px;
  color: #c9d1d9;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 4px;
}
.sc-name.ignored { color: #4d555e; text-decoration: line-through; }

.outline-pill {
  font-size: 8px;
  font-weight: 600;
  padding: 0 4px;
  border-radius: 3px;
  background: #1f6feb22;
  color: #7dd3fc;
  border: 1px solid #1f6feb44;
  letter-spacing: .04em;
  flex-shrink: 0;
}

.tag-row { display: flex; flex-wrap: wrap; gap: 3px; margin-top: 3px; }

.tag {
  font-size: 9px;
  font-weight: 500;
  padding: 0 5px;
  border-radius: 10px;
  letter-spacing: .03em;
}
.t-smoke      { background: #064e3b; color: #6ee7b7; }
.t-regression { background: #1e3a5f; color: #7dd3fc; }
.t-negative   { background: #450a0a; color: #fca5a5; }
.t-ignore     { background: #1f2937; color: #6b7280; }
.t-property   { background: #2e1065; color: #c084fc; }
.t-feature    { background: #431407; color: #fb923c; }
.t-other      { background: #1c2128; color: #8b949e; }

.sc-run {
  opacity: 0;
  background: none;
  border: none;
  color: #388bfd;
  cursor: pointer;
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 3px;
  transition: opacity .12s;
  flex-shrink: 0;
  margin-top: 1px;
}
.sc-run:hover { background: #1f6feb33; }

.empty {
  padding: 32px 16px;
  text-align: center;
  color: #6e7681;
  font-size: 11px;
  line-height: 1.8;
}

.spinner-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 16px;
  color: #6e7681;
  font-size: 11px;
}
</style>
</head>
<body>

<div class="header">
  <span class="logo">◈</span>
  <span class="header-title">Scenario Explorer</span>
  <button class="icon-btn" id="refresh-btn" title="Refresh">↻</button>
  <button class="run-all-btn" id="run-all-btn" title="Run all tests">▶ All</button>
</div>

<div class="stats" id="stats"></div>
<div id="features"><div class="spinner-wrap"><span>↻</span><span>Scanning features…</span></div></div>

<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
let features = [];
let collapsed = new Set();

const featRoot = document.getElementById('features');
const statsEl  = document.getElementById('stats');

// ── message bus ──────────────────────────────────────────────────────────────
window.addEventListener('message', e => {
  const msg = e.data;
  if (msg.type === 'update') { features = msg.features; render(); }
});

// ── toolbar ───────────────────────────────────────────────────────────────────
document.getElementById('refresh-btn').addEventListener('click', () =>
  vscode.postMessage({ type: 'refresh' }));

document.getElementById('run-all-btn').addEventListener('click', () =>
  vscode.postMessage({ type: 'runAll' }));

// ── event delegation ──────────────────────────────────────────────────────────
featRoot.addEventListener('click', e => {
  const target = e.target;

  // Run feature
  const featRun = target.closest('[data-feat-run]');
  if (featRun) {
    e.stopPropagation();
    vscode.postMessage({ type: 'runFeature', fsPath: featRun.dataset.featRun });
    return;
  }

  // Run scenario
  const scRun = target.closest('[data-sc-run]');
  if (scRun) {
    e.stopPropagation();
    vscode.postMessage({ type: 'runScenario', scenarioName: scRun.dataset.scRun });
    return;
  }

  // Toggle feature
  const featHdr = target.closest('[data-feat-idx]');
  if (featHdr && 'featIdx' in featHdr.dataset) {
    const idx = featHdr.dataset.featIdx;
    if (collapsed.has(idx)) collapsed.delete(idx);
    else collapsed.add(idx);
    render();
    return;
  }

  // Open file at scenario
  const scRow = target.closest('[data-uri]');
  if (scRow) {
    vscode.postMessage({ type: 'openFile', uri: scRow.dataset.uri, line: Number(scRow.dataset.line) });
  }
});

// ── render ────────────────────────────────────────────────────────────────────
function render() {
  const total   = features.reduce((n, f) => n + f.scenarios.length, 0);
  const ignored = features.reduce((n, f) => n + f.scenarios.filter(s => s.isIgnored).length, 0);
  const active  = total - ignored;

  statsEl.innerHTML =
    chip('#238636', active  + ' active') + ' ' +
    chip('#4d555e', ignored + ' skipped');

  if (features.length === 0) {
    featRoot.innerHTML =
      '<div class="empty">No .feature files found.<br>Add one to see your scenarios here.</div>';
    return;
  }

  featRoot.innerHTML = features.map(renderFeature).join('');
}

function chip(color, label) {
  return '<span class="stat-chip"><span class="stat-dot" style="background:' + color + '"></span>' + esc(label) + '</span>';
}

function renderFeature(f, i) {
  const idx  = String(i);
  const open = !collapsed.has(idx);
  const featTags = f.tags.map(t => '<span class="tag t-feature">' + esc(t) + '</span>').join('');
  const body = open ? f.scenarios.map(s => renderScenario(s, f.uri, f.name)).join('') : '';

  return (
    '<div class="feature-section">' +
    '<div class="feature-header" data-feat-idx="' + idx + '" title="' + esc(f.fsPath) + '">' +
      '<span class="chevron">' + (open ? '▾' : '▸') + '</span>' +
      '<span class="feat-name">' + esc(f.name) + '</span>' +
      '<div class="feat-tags">' + featTags + '</div>' +
      '<span class="feat-count">' + f.scenarios.length + '</span>' +
      '<button class="feat-run" data-feat-run="' + esc(f.fsPath) + '" title="Run feature">▶</button>' +
    '</div>' +
    '<div class="feature-body">' + body + '</div>' +
    '</div>'
  );
}

function renderScenario(s, featureUri, featureName) {
  const ignored = s.isIgnored;
  const dotCls  = ignored ? 'dot-ignored'
                : s.tags.some(t => /^property/i.test(t)) ? 'dot-property'
                : s.isOutline ? 'dot-outline'
                : 'dot-active';

  const nameCls = 'sc-name' + (ignored ? ' ignored' : '');
  const outline = s.isOutline ? '<span class="outline-pill">OUTLINE</span>' : '';

  const tags = s.tags
    .map(t => '<span class="tag ' + tagCls(t) + '">' + esc(t) + '</span>')
    .join('');

  return (
    '<div class="scenario-row" data-uri="' + esc(featureUri) + '" data-line="' + s.line + '">' +
      '<div class="dot ' + dotCls + '"></div>' +
      '<div class="sc-info">' +
        '<div class="' + nameCls + '">' + esc(s.name) + outline + '</div>' +
        (tags ? '<div class="tag-row">' + tags + '</div>' : '') +
      '</div>' +
      (ignored ? '' : '<button class="sc-run" data-sc-run="' + esc(s.name) + '" title="Run scenario">▶</button>') +
    '</div>'
  );
}

function tagCls(t) {
  const l = t.toLowerCase();
  if (l === 'smoke')      return 't-smoke';
  if (l === 'regression') return 't-regression';
  if (l === 'negative')   return 't-negative';
  if (l === 'ignore')     return 't-ignore';
  if (l.startsWith('property')) return 't-property';
  if (/^(calculator|shopping|auth|feature)/.test(l)) return 't-feature';
  return 't-other';
}

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
</script>
</body>
</html>`;
  }
}

// ── parser ──────────────────────────────────────────────────────────────────

const TAG_RE      = /^\s*(@[\w(),"' ]+)\s*$/;
const FEATURE_RE  = /^\s*Feature:\s*(.+)$/;
const SCENARIO_RE = /^\s*(Scenario Outline:|Scenario Template:|Scenario:|Example:)\s*(.*)$/;

function parseFeature(uri: vscode.Uri, content: string): FeatureItem | null {
  const lines       = content.split('\n');
  let featureName   = '';
  let featureTags: string[] = [];
  let pending: string[]     = [];
  const scenarios: ScenarioItem[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    const tagMatch = TAG_RE.exec(line);
    if (tagMatch) {
      const tags = tagMatch[1].trim().split(/\s+/).map(t => t.replace(/^@/, ''));
      pending.push(...tags);
      continue;
    }

    const featMatch = FEATURE_RE.exec(line);
    if (featMatch) {
      featureName = featMatch[1].trim();
      featureTags = [...pending];
      pending     = [];
      continue;
    }

    const scMatch = SCENARIO_RE.exec(line);
    if (scMatch) {
      const isOutline = scMatch[1].includes('Outline') || scMatch[1].includes('Template');
      const tags      = [...pending];
      pending         = [];
      scenarios.push({
        name:      scMatch[2].trim(),
        line:      i + 1,
        tags,
        isOutline,
        isIgnored: tags.includes('ignore'),
      });
      continue;
    }

    // Any non-empty non-comment line clears pending tags
    if (line.trim() && !line.trim().startsWith('#') && !line.trim().startsWith('|')) {
      pending = [];
    }
  }

  if (!featureName) return null;
  return { uri: uri.toString(), fsPath: uri.fsPath, name: featureName, tags: featureTags, scenarios };
}

function nonce32(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  for (let i = 0; i < 32; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}
