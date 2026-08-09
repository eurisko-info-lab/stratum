import * as path from 'path';
import * as vscode from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions
} from 'vscode-languageclient/node';

/**
 * The editor half of Stratum Studio.
 *
 * There is no language knowledge in this file. The languages, their file
 * extensions, their highlighting and the available commands are all generated
 * into package.json by `stratum studio package`, read back from the extension
 * manifest at activation, and answered by judgments of the world.
 */

let client: LanguageClient | undefined;

interface View {
  name: string;
  items: string[];
}

interface LspTraceEntry {
  kind: 'request' | 'response' | 'error';
  method: string;
  body: unknown;
}

interface ViewPlacement {
  name: string;
  placement: string;
  primitive: string;
}

interface Layout {
  name: string;
  workflow: string[];
  navigation: string;
  navigator: { name: string; title: string; reveal: boolean } | null;
  views: ViewPlacement[];
}

interface Report {
  name: string;
  path: string;
  findings: number;
  hazards: number;
}

interface Subject {
  name: string;
  reports: Report[];
}

type CatalogueNode =
  | { kind: 'subject'; subject: Subject }
  | { kind: 'report'; report: Report };

interface LanguageConfiguration {
  id: string;
  lineComment: string | null;
}

interface StudioSnapshot {
  activeDocument: string | null;
  languages: string[];
  layout: Layout | null;
  views: Record<string, string[]>;
  documents: Subject[];
  output: string[];
  previewOpen: boolean;
  trace: LspTraceEntry[];
}


/** One view of the profile, shown wherever the profile places it. */
class RegionView implements vscode.TreeDataProvider<string> {
  private readonly emitter = new vscode.EventEmitter<string | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  private items: string[] = [];

  constructor(private readonly primitive: string) {}

  refresh(items: string[]): void {
    this.items = items;
    this.emitter.fire(undefined);
  }

  getTreeItem(item: string): vscode.TreeItem {
    const node = new vscode.TreeItem(item, vscode.TreeItemCollapsibleState.None);
    node.iconPath = new vscode.ThemeIcon(iconFor(this.primitive));
    return node;
  }

  getChildren(item?: string): string[] {
    return item ? [] : this.items;
  }

  snapshot(): string[] {
    return [...this.items];
  }
}

/** The world's catalogue: what there is, not what one buffer contains. */
class CatalogueView implements vscode.TreeDataProvider<CatalogueNode> {
  private readonly emitter = new vscode.EventEmitter<CatalogueNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  private subjects: Subject[] = [];

  constructor(private readonly root: vscode.Uri) {}

  refresh(subjects: Subject[]): void {
    this.subjects = subjects;
    this.emitter.fire(undefined);
  }

  getTreeItem(node: CatalogueNode): vscode.TreeItem {
    if (node.kind === 'subject') {
      const item = new vscode.TreeItem(
        node.subject.name,
        vscode.TreeItemCollapsibleState.Expanded
      );
      item.iconPath = new vscode.ThemeIcon('beaker');
      item.description = `${node.subject.reports.length} reports`;
      return item;
    }
    const item = new vscode.TreeItem(node.report.name, vscode.TreeItemCollapsibleState.None);
    item.iconPath = new vscode.ThemeIcon(node.report.findings > 0 ? 'warning' : 'file-text');
    const notes: string[] = [];
    if (node.report.hazards > 0) {
      notes.push(`${node.report.hazards} hazards`);
    }
    if (node.report.findings > 0) {
      notes.push(`${node.report.findings} findings`);
    }
    item.description = notes.join(' \u00b7 ');
    item.resourceUri = vscode.Uri.joinPath(this.root, node.report.path);
    item.command = {
      command: 'vscode.open',
      title: 'Open report',
      arguments: [item.resourceUri]
    };
    return item;
  }

  getChildren(node?: CatalogueNode): CatalogueNode[] {
    if (!node) {
      return this.subjects.map(subject => ({ kind: 'subject', subject }));
    }
    if (node.kind === 'subject') {
      return node.subject.reports.map(report => ({ kind: 'report', report }));
    }
    return [];
  }

  snapshot(): Subject[] {
    return this.subjects.map(subject => ({
      name: subject.name,
      reports: subject.reports.map(report => ({ ...report }))
    }));
  }
}

/** The PDF the world produced, rendered where the source can be seen too. */
function renderPdf(pdfjs: vscode.Uri, worker: vscode.Uri, base64: string, nonce: string): string {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline';
                 script-src 'nonce-${nonce}'; worker-src blob:;">
  <style>
    body { margin: 0; background: var(--vscode-editor-background); text-align: center; }
    canvas { max-width: 100%; margin: 1rem auto; box-shadow: 0 0 12px rgba(0,0,0,.4); }
    p.failed { font-family: var(--vscode-font-family); padding: 2rem; opacity: .7; }
  </style></head><body>
  <canvas id="page"></canvas>
  <script type="module" nonce="${nonce}">
    import * as pdfjs from '${pdfjs}';
    pdfjs.GlobalWorkerOptions.workerSrc = '${worker}';
    const bytes = Uint8Array.from(atob('${base64}'), c => c.charCodeAt(0));
    try {
      const doc = await pdfjs.getDocument({ data: bytes }).promise;
      const page = await doc.getPage(1);
      const scale = 1.4;
      const viewport = page.getViewport({ scale });
      const canvas = document.getElementById('page');
      const context = canvas.getContext('2d');
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      await page.render({ canvasContext: context, viewport }).promise;
    } catch (error) {
      document.body.innerHTML = '<p class="failed">' + String(error) + '</p>';
    }
  </script></body></html>`;
}

function iconFor(primitive: string): string {
  switch (primitive) {
    case 'tree':
      return 'list-tree';
    case 'table':
      return 'table';
    case 'list':
      return 'list-unordered';
    case 'preview':
      return 'file-pdf';
    default:
      return 'circle-outline';
  }
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) {
    return;
  }

  const settings = vscode.workspace.getConfiguration('stratum');
  const world = process.env.STRATUM_STUDIO_WORLD ?? settings.get<string>('world') ?? 'applications/sds';
  const server = process.env.STRATUM_STUDIO_SERVER ?? settings.get<string>('server') ?? './tools/lsp.sh';
  const command = path.isAbsolute(server) ? server : path.join(folder.uri.fsPath, server);

  const contributes = context.extension.packageJSON.contributes ?? {};
  const languages: string[] = (contributes.languages ?? []).map((l: { id: string }) => l.id);

  const serverOptions: ServerOptions = {
    command,
    args: ['--world', world],
    options: { cwd: folder.uri.fsPath }
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: languages.map(id => ({ scheme: 'file', language: id })),
    outputChannelName: 'Stratum'
  };

  const trace: LspTraceEntry[] = [];
  const outputLines: string[] = [];
  let declaredLanguages: LanguageConfiguration[] = [];
  let currentLayout: Layout | null = null;
  let lastRefresh: Promise<void> = Promise.resolve();

  const request = async <T>(method: string, body: unknown): Promise<T> => {
    if (!client) {
      throw new Error(`Stratum client is not available for ${method}`);
    }
    trace.push({ kind: 'request', method, body });
    try {
      const result = await client.sendRequest<T>(method, body);
      trace.push({ kind: 'response', method, body: result });
      return result;
    } catch (error) {
      trace.push({
        kind: 'error',
        method,
        body: error instanceof Error ? error.message : String(error)
      });
      throw error;
    }
  };

  client = new LanguageClient('stratum', 'Stratum', serverOptions, clientOptions);
  await client.start();

  // Comment tokens and brackets are the world's to declare, so they are asked
  // for at activation rather than shipped as generated files. Highlighting
  // arrives the same way, as semantic tokens over the grammar's own classes.
  declaredLanguages = await request<LanguageConfiguration[]>('stratum/languages', {})
    .catch(() => [] as LanguageConfiguration[]);
  for (const language of declaredLanguages) {
    context.subscriptions.push(
      vscode.languages.setLanguageConfiguration(language.id, {
        comments: language.lineComment ? { lineComment: language.lineComment } : undefined,
        brackets: [
          ['[', ']'],
          ['(', ')'],
          ['{', '}']
        ]
      })
    );
  }

  // The layout is the profile's, not the client's.
  currentLayout = await request<Layout | null>('stratum/layout', {})
    .catch(() => null);
  const panels = new Map<string, RegionView>();
  for (const placed of currentLayout?.views ?? []) {
    const view = new RegionView(placed.primitive);
    panels.set(placed.name, view);
    context.subscriptions.push(
      vscode.window.registerTreeDataProvider(`stratum.view.${placed.name}`, view)
    );
  }

  // The workflow the profile declares, shown as the document's stages.
  const stages = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  if (currentLayout && currentLayout.workflow.length > 0) {
    stages.text = `$(git-merge) ${currentLayout.workflow.join(' \u2192 ')}`;
    stages.tooltip = `${currentLayout.name} \u00b7 ${currentLayout.navigation}`;
    stages.show();
  }
  context.subscriptions.push(stages);

  const catalogue = new CatalogueView(folder.uri);
  if (currentLayout?.navigator) {
    context.subscriptions.push(
      vscode.window.registerTreeDataProvider(`stratum.view.${currentLayout.navigator.name}`, catalogue)
    );
    // Which view the editor opens on is the world's decision, not the client's.
    if (currentLayout.navigator.reveal) {
      await vscode.commands
        .executeCommand(`stratum.view.${currentLayout.navigator.name}.focus`)
        .then(undefined, () => undefined);
    }
  }

  let preview: vscode.WebviewPanel | undefined;
  let previewClosed = false;

  const pdfjsRoot = vscode.Uri.joinPath(context.extensionUri, 'node_modules', 'pdfjs-dist', 'build');

  const showPreview = async (document: vscode.TextDocument): Promise<void> => {
    if (!client || previewClosed) {
      return;
    }
    if (!preview) {
      preview = vscode.window.createWebviewPanel(
        'stratum.preview',
        'Report',
        { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
        { enableScripts: true, localResourceRoots: [pdfjsRoot] }
      );
      preview.onDidDispose(() => {
        preview = undefined;
        previewClosed = true;
      });
    }
    const produced = await request<string>('stratum/pdf', {
      uri: document.uri.toString()
    });
    const bytes = Buffer.from(produced, 'latin1');
    const nonce = Math.random().toString(36).slice(2);
    preview.webview.html = renderPdf(
      preview.webview.asWebviewUri(vscode.Uri.joinPath(pdfjsRoot, 'pdf.mjs')),
      preview.webview.asWebviewUri(vscode.Uri.joinPath(pdfjsRoot, 'pdf.worker.mjs')),
      bytes.toString('base64'),
      nonce
    );
  };

  const refresh = async (): Promise<void> => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || !client || !languages.includes(editor.document.languageId)) {
      panels.forEach(view => view.refresh([]));
      return;
    }
    try {
      const result = await request<View[]>('stratum/views', {
        uri: editor.document.uri.toString()
      });
      const byName = new Map<string, string[]>(result.map(view => [view.name, view.items]));
      panels.forEach((view, name) => view.refresh(byName.get(name) ?? []));
      catalogue.refresh(await request<Subject[]>('stratum/documents', {}));
      if (currentLayout?.views.some(view => view.primitive === 'preview')) {
        await showPreview(editor.document);
      }
    } catch {
      panels.forEach(view => view.refresh([]));
    }
  };

  const scheduleRefresh = (): void => {
    lastRefresh = refresh();
    void lastRefresh;
  };

  context.subscriptions.push(
    vscode.window.onDidChangeActiveTextEditor(() => scheduleRefresh()),
    vscode.workspace.onDidSaveTextDocument(() => scheduleRefresh()),
    vscode.workspace.onDidChangeTextDocument(() => scheduleRefresh())
  );

  // Evaluating a selection: the editor knows where the cursor is, the world
  // knows what the text means. Nothing here interprets the answer.
  const output = vscode.window.createOutputChannel('Stratum');
  context.subscriptions.push(output);

  const appendOutput = (line: string): void => {
    outputLines.push(line);
    output.appendLine(line);
  };

  context.subscriptions.push(
    vscode.commands.registerCommand('stratum.test.snapshot', async (): Promise<StudioSnapshot> => {
      await lastRefresh;
      return {
        activeDocument: vscode.window.activeTextEditor?.document.uri.toString() ?? null,
        languages: declaredLanguages.map(language => language.id),
        layout: currentLayout,
        views: Object.fromEntries([...panels.entries()].map(([name, view]) => [name, view.snapshot()])),
        documents: catalogue.snapshot(),
        output: [...outputLines],
        previewOpen: preview !== undefined,
        trace: trace.map(entry => ({ ...entry }))
      };
    }),
    vscode.commands.registerCommand('stratum.test.clearTrace', (): void => {
      trace.length = 0;
      outputLines.length = 0;
      output.clear();
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('stratum.evaluate', async () => {
      const editor = vscode.window.activeTextEditor;
      if (!editor || !client) {
        return;
      }
      const document = editor.document;
      const selection = editor.selection;
      const offset = document.offsetAt(selection.start);
      const length = document.offsetAt(selection.end) - offset;
      const answer = await request<string>('stratum/evaluate', {
        uri: document.uri.toString(),
        offset,
        length
      });
      if (!answer) {
        return;
      }
      appendOutput(answer);
      output.show(true);
      void vscode.window.setStatusBarMessage(answer.split('\n')[0], 5000);
    })
  );

  // The commands are the deployed Studio profile's commands, and each one is
  // answered by the foundation rather than by this extension.
  for (const entry of contributes.commands ?? []) {
    const id: string = entry.command;
    if (id === 'stratum.evaluate') {
      continue;
    }
    context.subscriptions.push(
      vscode.commands.registerCommand(id, async () => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || !client) {
          return;
        }
        const answer = await request<string>('workspace/executeCommand', {
          command: id,
          arguments: [editor.document.uri.toString()]
        });
        void vscode.window.showInformationMessage(answer);
        await refresh();
      })
    );
  }

  lastRefresh = refresh();
  await lastRefresh;
}

export async function deactivate(): Promise<void> {
  await client?.stop();
  client = undefined;
}
