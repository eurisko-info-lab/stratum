import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions
} from 'vscode-languageclient/node';

let languageClient: LanguageClient | undefined;

type Value =
  | { kind: 'unit' }
  | { kind: 'bool'; value: boolean }
  | { kind: 'nat' | 'int'; value: bigint }
  | { kind: 'bytes'; value: Buffer }
  | { kind: 'string' | 'symbol' | 'ref'; value: string }
  | { kind: 'list'; items: Value[] }
  | { kind: 'map'; entries: [Value, Value][] }
  | { kind: 'node'; tag: string; args: Value[] };

class CanonReader {
  private offset = 0;
  constructor(private readonly bytes: Buffer) {}

  read(): Value {
    const tag = this.byte();
    switch (tag) {
      case 0: return {kind: 'unit'};
      case 1: return {kind: 'bool', value: this.byte() !== 0};
      case 2: return {kind: 'nat', value: this.varint()};
      case 3: {
        const encoded = this.varint();
        return {kind: 'int', value: (encoded & 1n) === 0n ? encoded / 2n : -(encoded + 1n) / 2n};
      }
      case 4: return {kind: 'bytes', value: this.data()};
      case 5: return {kind: 'string', value: this.data().toString('utf8')};
      case 6: return {kind: 'symbol', value: this.data().toString('utf8')};
      case 7: return {kind: 'ref', value: this.take(32).toString('hex')};
      case 8: return {kind: 'list', items: this.many()};
      case 9: {
        const count = Number(this.varint());
        const entries: [Value, Value][] = [];
        for (let i = 0; i < count; i += 1) entries.push([this.read(), this.read()]);
        return {kind: 'map', entries};
      }
      case 10: {
        const name = this.data().toString('utf8');
        const count = Number(this.varint());
        const args: Value[] = [];
        for (let i = 0; i < count; i += 1) args.push(this.read());
        return {kind: 'node', tag: name, args};
      }
      default: throw new Error(`unknown canonical tag ${tag}`);
    }
  }

  private many(): Value[] {
    const count = Number(this.varint());
    const values: Value[] = [];
    for (let i = 0; i < count; i += 1) values.push(this.read());
    return values;
  }
  private data(): Buffer { return this.take(Number(this.varint())); }
  private byte(): number {
    if (this.offset >= this.bytes.length) throw new Error('truncated canonical value');
    return this.bytes[this.offset++];
  }
  private take(count: number): Buffer {
    const end = this.offset + count;
    if (end > this.bytes.length) throw new Error('truncated canonical value');
    const result = this.bytes.subarray(this.offset, end);
    this.offset = end;
    return result;
  }
  private varint(): bigint {
    let value = 0n;
    let shift = 0n;
    while (true) {
      const next = this.byte();
      value |= BigInt(next & 0x7f) << shift;
      if ((next & 0x80) === 0) return value;
      shift += 7n;
    }
  }
}

interface Block { digest: string; height: number; previous?: string; patch: string; }
interface Patch { tree: string; message: string; changes: Change[]; }
interface Change { kind: string; path: string; before?: string; after?: string; }
interface StoredFile { path: string; blob: string; }

function node(value: Value, tag: string): Extract<Value, {kind: 'node'}> {
  if (value.kind !== 'node' || value.tag !== tag) throw new Error(`expected ${tag}`);
  return value;
}
function text(value: Value): string {
  if (value.kind !== 'string' && value.kind !== 'symbol' && value.kind !== 'ref') throw new Error('expected text');
  return value.value;
}

class Repository {
  constructor(readonly root: string, readonly branch = 'main') {}

  head(): string { return fs.readFileSync(path.join(this.root, 'refs', this.branch), 'utf8').trim(); }
  artifact(digest: string): {kind: string; body: Value} {
    const envelope = node(new CanonReader(fs.readFileSync(path.join(this.root, 'objects', `${digest}.canon`))).read(), 'artifact');
    return {kind: text(envelope.args[0]), body: envelope.args[1]};
  }
  block(digest: string): Block {
    const value = node(this.artifact(digest).body, 'block');
    const predecessor = value.args[1];
    return {
      digest,
      height: Number((value.args[0] as {value: bigint}).value),
      previous: predecessor.kind === 'ref' ? predecessor.value : undefined,
      patch: text(value.args[2])
    };
  }
  patch(digest: string): Patch {
    const value = node(this.artifact(digest).body, 'patch');
    const changes = value.args[3].kind === 'list' ? value.args[3].items.map(item => {
      const change = item as Extract<Value, {kind: 'node'}>;
      switch (change.tag) {
        case 'add': return {kind: change.tag, path: text(change.args[0]), after: text(change.args[1])};
        case 'remove': return {kind: change.tag, path: text(change.args[0]), before: text(change.args[1])};
        case 'replace': return {
          kind: change.tag, path: text(change.args[0]),
          before: text(change.args[1]), after: text(change.args[2])
        };
        default: return {kind: change.tag, path: text(change.args[0])};
      }
    }) : [];
    return {tree: text(value.args[1]), message: text(value.args[2]), changes};
  }
  files(block = this.head()): StoredFile[] {
    const tree = node(this.artifact(this.patch(this.block(block).patch).tree).body, 'tree');
    const entries = tree.args[0];
    if (entries.kind !== 'list') return [];
    return entries.items.map(item => {
      const entry = node(item, 'entry');
      return {path: text(entry.args[0]), blob: text(entry.args[1])};
    });
  }
  content(file: string, block: string): Buffer {
    const entry = this.files(block).find(candidate => candidate.path === file);
    if (!entry) return Buffer.from(`File ${file} does not exist at ${block}.\n`);
    const blob = node(this.artifact(entry.blob).body, 'blob');
    if (blob.args[0].kind !== 'bytes') throw new Error('invalid blob');
    return blob.args[0].value;
  }
  blob(digest: string): string {
    const blob = node(this.artifact(digest).body, 'blob');
    if (blob.args[0].kind !== 'bytes') throw new Error('invalid blob');
    return blob.args[0].value.toString('utf8');
  }
  chain(): {block: Block; patch: Patch}[] {
    const result: {block: Block; patch: Patch}[] = [];
    let digest: string | undefined = this.head();
    while (digest) {
      const block = this.block(digest);
      result.push({block, patch: this.patch(block.patch)});
      digest = block.previous;
    }
    return result;
  }
}

interface FileNode { kind: 'directory' | 'file'; name: string; path: string; children: FileNode[]; }

function hierarchy(files: StoredFile[]): FileNode[] {
  const root: FileNode = {kind: 'directory', name: '', path: '', children: []};
  for (const file of files) {
    let parent = root;
    const parts = file.path.split('/');
    parts.forEach((part, index) => {
      const full = parts.slice(0, index + 1).join('/');
      let child = parent.children.find(candidate => candidate.name === part);
      if (!child) {
        child = {kind: index === parts.length - 1 ? 'file' : 'directory', name: part, path: full, children: []};
        parent.children.push(child);
      }
      parent = child;
    });
  }
  const sort = (nodes: FileNode[]): void => {
    nodes.sort((a, b) => a.kind === b.kind ? a.name.localeCompare(b.name) : a.kind === 'directory' ? -1 : 1);
    nodes.forEach(item => sort(item.children));
  };
  sort(root.children);
  return root.children;
}

class StoredFiles implements vscode.TreeDataProvider<FileNode> {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;
  private roots: FileNode[] = [];
  constructor(private readonly repository: Repository) { this.refresh(); }
  refresh(): void { this.roots = hierarchy(this.repository.files()); this.changed.fire(); }
  getChildren(node?: FileNode): FileNode[] { return node ? node.children : this.roots; }
  getTreeItem(node: FileNode): vscode.TreeItem {
    const item = new vscode.TreeItem(node.name, node.kind === 'directory'
      ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None);
    item.iconPath = new vscode.ThemeIcon(node.kind === 'directory' ? 'folder' : 'file');
    item.tooltip = node.path;
    item.command = {command: 'stratumRepo.select', title: 'Select stored path', arguments: [node]};
    return item;
  }
}

type HistoryNode =
  | {
      kind: 'commit'; height: number; digest: string; previous?: string;
      message: string; change: string; file: string; changes: Change[]; context: boolean
    }
  | {kind: 'context'; label: string; commits: HistoryNode[]}
  | {
      kind: 'path-change'; height: number; digest: string; previous?: string;
      message: string; path: string; change: Change
    };

function changeLabel(change: Change): string {
  return `${change.kind} ${change.path}`;
}

function summarizeChanges(changes: Change[]): string {
  const shown = changes.slice(0, 2).map(changeLabel);
  if (changes.length > shown.length) shown.push(`+${changes.length - shown.length} more`);
  return shown.join(' · ');
}

class CausalHistory implements vscode.TreeDataProvider<HistoryNode> {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;
  private nodes: HistoryNode[] = [];
  private selected = '';
  constructor(private readonly repository: Repository) {}
  select(file: string): void {
    this.selected = file;
    const directory = file.endsWith('/');
    const all = this.repository.chain().map(({block, patch}) => {
      const relevant = patch.changes.filter(change =>
        directory ? change.path.startsWith(file) : change.path === file);
      const labels = relevant.map(change => {
        if (change.kind === 'remove' && change.before) {
          const destination = patch.changes.find(candidate =>
            candidate.kind === 'add' && candidate.after === change.before);
          if (destination) return `move to ${destination.path}`;
        }
        if (change.kind === 'add' && change.after) {
          const source = patch.changes.find(candidate =>
            candidate.kind === 'remove' && candidate.before === change.after);
          if (source) return `move from ${source.path}`;
        }
        return change.kind;
      });
      return {block, patch, relevant, labels};
    });
    const result: HistoryNode[] = [];
    let hidden: HistoryNode[] = [];
    const flush = (): void => {
      if (hidden.length > 0) {
        const paths = new Set(hidden.flatMap(item => item.kind === 'commit' ? item.changes.map(change => change.path) : []));
        result.push({
          kind: 'context',
          label: `${hidden.length} commits affecting ${paths.size} other paths`,
          commits: hidden
        });
      }
      hidden = [];
    };
    for (const entry of all) {
      const context = entry.relevant.length === 0;
      const changes = context ? entry.patch.changes : entry.relevant;
      const commit: HistoryNode = {
        kind: 'commit', height: entry.block.height, digest: entry.block.digest, previous: entry.block.previous,
        message: entry.patch.message,
        change: context ? summarizeChanges(changes) : entry.labels.join(', '),
        file, changes, context
      };
      if (entry.relevant.length > 0) { flush(); result.push(commit); } else hidden.push(commit);
    }
    flush();
    this.nodes = result;
    this.changed.fire();
  }
  getChildren(node?: HistoryNode): HistoryNode[] {
    if (!node) return this.nodes;
    if (node.kind === 'context') return node.commits;
    if (node.kind === 'commit' && node.context) {
      return node.changes.map(change => ({
        kind: 'path-change',
        height: node.height,
        digest: node.digest,
        previous: node.previous,
        message: node.message,
        path: change.path,
        change
      }));
    }
    return [];
  }
  getTreeItem(node: HistoryNode): vscode.TreeItem {
    if (node.kind === 'context') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.Collapsed);
      item.iconPath = new vscode.ThemeIcon('fold');
      return item;
    }
    if (node.kind === 'path-change') {
      const item = new vscode.TreeItem(changeLabel(node.change), vscode.TreeItemCollapsibleState.None);
      item.iconPath = new vscode.ThemeIcon(
        node.change.kind === 'add' ? 'diff-added' :
        node.change.kind === 'remove' ? 'diff-removed' : 'diff-modified'
      );
      item.command = {command: 'stratumRepo.openRevision', title: 'Open path change', arguments: [node]};
      return item;
    }
    const item = new vscode.TreeItem(
      `#${node.height} ${node.message}`,
      node.context && node.changes.length > 0
        ? vscode.TreeItemCollapsibleState.Collapsed
        : vscode.TreeItemCollapsibleState.None
    );
    item.description = node.change;
    item.tooltip = node.digest;
    item.iconPath = new vscode.ThemeIcon(node.context ? 'circle-outline' : 'git-commit');
    if (!node.context) {
      item.command = {command: 'stratumRepo.openRevision', title: 'Open historical revision', arguments: [node]};
    }
    return item;
  }
  selectedPath(): string { return this.selected; }
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder || !fs.existsSync(path.join(folder.uri.fsPath, 'format'))) return;
  const repository = new Repository(folder.uri.fsPath);
  const history = new CausalHistory(repository);
  const files = new StoredFiles(repository);
  const fileView = vscode.window.createTreeView('stratumRepo.files', {
    treeDataProvider: files,
    showCollapseAll: true
  });
  const historyView = vscode.window.createTreeView('stratumRepo.history', {
    treeDataProvider: history,
    showCollapseAll: true
  });
  context.subscriptions.push(fileView, historyView);
  const scheme = 'stratum-repo';
  context.subscriptions.push(vscode.workspace.registerTextDocumentContentProvider(scheme, {
    provideTextDocumentContent(uri: vscode.Uri): string {
      const params = new URLSearchParams(uri.query);
      return repository.content(uri.path.slice(1), params.get('block') ?? repository.head()).toString('utf8');
    }
  }));
  const selectStoredPath = async (item: FileNode): Promise<void> => {
    history.select(item.kind === 'directory' ? `${item.path}/` : item.path);
    await vscode.commands.executeCommand('stratumRepo.history.focus');
    if (item.kind === 'file') {
      const uri = vscode.Uri.from({scheme, path: `/${item.path}`, query: `block=${repository.head()}`});
      await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri), {preview: true});
    }
  };
  context.subscriptions.push(
    fileView.onDidChangeSelection(event => {
      const selected = event.selection[0];
      if (selected) void selectStoredPath(selected);
    }),
    vscode.commands.registerCommand('stratumRepo.select', selectStoredPath)
  );
  context.subscriptions.push(vscode.commands.registerCommand('stratumRepo.openRevision', async (item: HistoryNode) => {
    if (item.kind !== 'commit' && item.kind !== 'path-change') return;
    const file = item.kind === 'commit' ? item.file : item.path;
    const change = item.kind === 'commit'
      ? item.changes.find(candidate => candidate.path === item.file)
      : item.change;
    const after = vscode.Uri.from({scheme, path: `/${file}`, query: `block=${item.digest}`});
    if (!change || change.kind === 'add' || !item.previous) {
      await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(after), {preview: true});
      return;
    }
    const beforePath = file;
    const before = vscode.Uri.from({scheme, path: `/${beforePath}`, query: `block=${item.previous}`});
    await vscode.commands.executeCommand(
      'vscode.diff',
      before,
      after,
      `${file} — #${item.height} ${item.message}`,
      {preview: true}
    );
  }));
  context.subscriptions.push(vscode.commands.registerCommand('stratumRepo.refresh', () => {
    files.refresh();
    if (history.selectedPath()) history.select(history.selectedPath());
  }));

  const configuration = vscode.workspace.getConfiguration('stratumRepo');
  const javaHome = configuration.get<string>('javaHome') ?? '';
  const inheritedPath = process.env.PATH ?? '/usr/local/bin:/usr/bin:/bin';
  const serverPath = javaHome ? `${path.join(javaHome, 'bin')}:${inheritedPath}` : inheritedPath;
  const server: ServerOptions = {
    command: configuration.get<string>('server') ?? '/tmp/stratum-studio-sds/tools/lsp.sh',
    args: ['--world', configuration.get<string>('world') ?? '/tmp/stratum-studio-sds/applications/sds'],
    options: {cwd: folder.uri.fsPath, env: {...process.env, PATH: serverPath}}
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      {scheme: 'file', language: 'meta'},
      {scheme: 'file', language: 'grammar'},
      {scheme: 'stratum-repo', language: 'meta'},
      {scheme: 'stratum-repo', language: 'grammar'}
    ],
    outputChannelName: 'Stratum Meta/Grammar'
  };
  languageClient = new LanguageClient(
    'stratum-repository-language-service',
    'Stratum Meta and Grammar',
    server,
    clientOptions
  );
  await languageClient.start();

  const declared = await languageClient
    .sendRequest<{id: string; lineComment: string | null}[]>('stratum/languages', {})
    .catch(() => []);
  for (const language of declared.filter(item => item.id === 'meta' || item.id === 'grammar')) {
    context.subscriptions.push(vscode.languages.setLanguageConfiguration(language.id, {
      comments: language.lineComment ? {lineComment: language.lineComment} : undefined,
      brackets: [['[', ']'], ['(', ')'], ['{', '}']]
    }));
  }
  void vscode.commands.executeCommand('stratumRepo.files.focus').then(undefined, error => {
    console.error('Could not focus the Stratum repository view', error);
  });
}

export async function deactivate(): Promise<void> {
  await languageClient?.stop();
  languageClient = undefined;
}
