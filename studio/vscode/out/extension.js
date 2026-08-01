"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.activate = activate;
exports.deactivate = deactivate;
const path = require("path");
const vscode = require("vscode");
const node_1 = require("vscode-languageclient/node");
/**
 * The editor half of Stratum Studio.
 *
 * There is no language knowledge in this file. The languages, their file
 * extensions, their highlighting and the available commands are all generated
 * into package.json by `stratum studio package`, read back from the extension
 * manifest at activation, and answered by judgments of the world.
 */
let client;
/** One view of the profile, shown wherever the profile places it. */
class RegionView {
    primitive;
    emitter = new vscode.EventEmitter();
    onDidChangeTreeData = this.emitter.event;
    items = [];
    constructor(primitive) {
        this.primitive = primitive;
    }
    refresh(items) {
        this.items = items;
        this.emitter.fire(undefined);
    }
    getTreeItem(item) {
        const node = new vscode.TreeItem(item, vscode.TreeItemCollapsibleState.None);
        node.iconPath = new vscode.ThemeIcon(iconFor(this.primitive));
        return node;
    }
    getChildren(item) {
        return item ? [] : this.items;
    }
}
/** The world's catalogue: what there is, not what one buffer contains. */
class CatalogueView {
    root;
    emitter = new vscode.EventEmitter();
    onDidChangeTreeData = this.emitter.event;
    subjects = [];
    constructor(root) {
        this.root = root;
    }
    refresh(subjects) {
        this.subjects = subjects;
        this.emitter.fire(undefined);
    }
    getTreeItem(node) {
        if (node.kind === 'subject') {
            const item = new vscode.TreeItem(node.subject.name, vscode.TreeItemCollapsibleState.Expanded);
            item.iconPath = new vscode.ThemeIcon('beaker');
            item.description = `${node.subject.reports.length} reports`;
            return item;
        }
        const item = new vscode.TreeItem(node.report.name, vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon(node.report.findings > 0 ? 'warning' : 'file-text');
        const notes = [];
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
    getChildren(node) {
        if (!node) {
            return this.subjects.map(subject => ({ kind: 'subject', subject }));
        }
        if (node.kind === 'subject') {
            return node.subject.reports.map(report => ({ kind: 'report', report }));
        }
        return [];
    }
}
/** The PDF the world produced, rendered where the source can be seen too. */
function renderPdf(pdfjs, worker, base64, nonce) {
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
function iconFor(primitive) {
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
async function activate(context) {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        return;
    }
    const settings = vscode.workspace.getConfiguration('stratum');
    const world = settings.get('world') ?? 'applications/sds';
    const server = settings.get('server') ?? './tools/lsp.sh';
    const command = path.isAbsolute(server) ? server : path.join(folder.uri.fsPath, server);
    const contributes = context.extension.packageJSON.contributes ?? {};
    const languages = (contributes.languages ?? []).map((l) => l.id);
    const serverOptions = {
        command,
        args: ['--world', world],
        options: { cwd: folder.uri.fsPath }
    };
    const clientOptions = {
        documentSelector: languages.map(id => ({ scheme: 'file', language: id })),
        outputChannelName: 'Stratum'
    };
    client = new node_1.LanguageClient('stratum', 'Stratum', serverOptions, clientOptions);
    await client.start();
    // Comment tokens and brackets are the world's to declare, so they are asked
    // for at activation rather than shipped as generated files. Highlighting
    // arrives the same way, as semantic tokens over the grammar's own classes.
    const declared = await client
        .sendRequest('stratum/languages', {})
        .catch(() => []);
    for (const language of declared) {
        context.subscriptions.push(vscode.languages.setLanguageConfiguration(language.id, {
            comments: language.lineComment ? { lineComment: language.lineComment } : undefined,
            brackets: [
                ['[', ']'],
                ['(', ')'],
                ['{', '}']
            ]
        }));
    }
    // The layout is the profile's, not the client's.
    const layout = await client
        .sendRequest('stratum/layout', {})
        .catch(() => null);
    const panels = new Map();
    for (const placed of layout?.views ?? []) {
        const view = new RegionView(placed.primitive);
        panels.set(placed.name, view);
        context.subscriptions.push(vscode.window.registerTreeDataProvider(`stratum.view.${placed.name}`, view));
    }
    // The workflow the profile declares, shown as the document's stages.
    const stages = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    if (layout && layout.workflow.length > 0) {
        stages.text = `$(git-merge) ${layout.workflow.join(' \u2192 ')}`;
        stages.tooltip = `${layout.name} \u00b7 ${layout.navigation}`;
        stages.show();
    }
    context.subscriptions.push(stages);
    const catalogue = new CatalogueView(folder.uri);
    if (layout?.navigator) {
        context.subscriptions.push(vscode.window.registerTreeDataProvider(`stratum.view.${layout.navigator.name}`, catalogue));
        // Which view the editor opens on is the world's decision, not the client's.
        if (layout.navigator.reveal) {
            await vscode.commands
                .executeCommand(`stratum.view.${layout.navigator.name}.focus`)
                .then(undefined, () => undefined);
        }
    }
    let preview;
    let previewClosed = false;
    const pdfjsRoot = vscode.Uri.joinPath(context.extensionUri, 'node_modules', 'pdfjs-dist', 'build');
    const showPreview = async (document) => {
        if (!client || previewClosed) {
            return;
        }
        if (!preview) {
            preview = vscode.window.createWebviewPanel('stratum.preview', 'Report', { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true }, { enableScripts: true, localResourceRoots: [pdfjsRoot] });
            preview.onDidDispose(() => {
                preview = undefined;
                previewClosed = true;
            });
        }
        const produced = await client.sendRequest('stratum/pdf', {
            uri: document.uri.toString()
        });
        const bytes = Buffer.from(produced, 'latin1');
        const nonce = Math.random().toString(36).slice(2);
        preview.webview.html = renderPdf(preview.webview.asWebviewUri(vscode.Uri.joinPath(pdfjsRoot, 'pdf.mjs')), preview.webview.asWebviewUri(vscode.Uri.joinPath(pdfjsRoot, 'pdf.worker.mjs')), bytes.toString('base64'), nonce);
    };
    const refresh = async () => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || !client || !languages.includes(editor.document.languageId)) {
            panels.forEach(view => view.refresh([]));
            return;
        }
        try {
            const result = await client.sendRequest('stratum/views', {
                uri: editor.document.uri.toString()
            });
            const byName = new Map(result.map(view => [view.name, view.items]));
            panels.forEach((view, name) => view.refresh(byName.get(name) ?? []));
            catalogue.refresh(await client.sendRequest('stratum/catalogue', {}));
            await showPreview(editor.document);
        }
        catch {
            panels.forEach(view => view.refresh([]));
        }
    };
    context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(() => void refresh()), vscode.workspace.onDidSaveTextDocument(() => void refresh()), vscode.workspace.onDidChangeTextDocument(() => void refresh()));
    // Evaluating a selection: the editor knows where the cursor is, the world
    // knows what the text means. Nothing here interprets the answer.
    const output = vscode.window.createOutputChannel('Stratum');
    context.subscriptions.push(output);
    context.subscriptions.push(vscode.commands.registerCommand('stratum.evaluate', async () => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || !client) {
            return;
        }
        const document = editor.document;
        const selection = editor.selection;
        const offset = document.offsetAt(selection.start);
        const length = document.offsetAt(selection.end) - offset;
        const answer = await client.sendRequest('stratum/evaluate', {
            uri: document.uri.toString(),
            offset,
            length
        });
        if (!answer) {
            return;
        }
        output.appendLine(answer);
        output.show(true);
        void vscode.window.setStatusBarMessage(answer.split('\n')[0], 5000);
    }));
    // The commands are the deployed Studio profile's commands, and each one is
    // answered by the foundation rather than by this extension.
    for (const entry of contributes.commands ?? []) {
        const id = entry.command;
        context.subscriptions.push(vscode.commands.registerCommand(id, async () => {
            const editor = vscode.window.activeTextEditor;
            if (!editor || !client) {
                return;
            }
            const answer = await client.sendRequest('workspace/executeCommand', {
                command: id,
                arguments: [editor.document.uri.toString()]
            });
            void vscode.window.showInformationMessage(answer);
            await refresh();
        }));
    }
    await refresh();
}
async function deactivate() {
    await client?.stop();
    client = undefined;
}
//# sourceMappingURL=extension.js.map