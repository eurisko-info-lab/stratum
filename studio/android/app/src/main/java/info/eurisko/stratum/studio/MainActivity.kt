package info.eurisko.stratum.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val Ink = Color(0xFF17201D)
private val Paper = Color(0xFFF4F7F5)
private val Teal = Color(0xFF006B5F)
private val Amber = Color(0xFFF0B429)
private val StudioColors = androidx.compose.material3.lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Color(0xFF52665F),
    tertiary = Color(0xFF8B5E00),
    background = Paper,
    surface = Color.White,
    onSurface = Ink,
    error = Color(0xFFBA1A1A)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = StudioColors) {
                val model: StudioViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) model.openDocument(this, uri)
                }
                StudioApp(
                    state,
                    model,
                    onOpen = { picker.launch(arrayOf("text/*", "application/json")) },
                    onSave = { model.saveDocument(this) }
                )
            }
        }
    }
}

internal enum class StudioTab(val label: String) { Editor("Editor"), World("World"), Output("Output") }

internal data class Diagnostic(val message: String, val severity: Int, val line: Int)
internal data class WorldView(val name: String, val items: List<String>)
internal data class Report(val name: String, val path: String, val findings: Int, val hazards: Int)
internal data class Subject(val name: String, val reports: List<Report>)
internal data class VirtualApp(
    val name: String,
    val title: String,
    val workflow: List<String>,
    val languages: List<String>
)

internal fun evaluationRange(value: TextFieldValue): Pair<Int, Int> {
    val offset = minOf(value.selection.start, value.selection.end).coerceAtMost(value.text.length)
    val length = (maxOf(value.selection.start, value.selection.end) - offset)
        .coerceAtMost(value.text.length - offset)
    return offset to length
}

internal data class StudioState(
    val host: String = "10.0.2.2",
    val port: String = "2087",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val serviceName: String = "Stratum Studio",
    val workflow: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val views: List<WorldView> = emptyList(),
    val tab: StudioTab = StudioTab.Editor,
    val documentName: String? = null,
    val documentUri: String? = null,
    val editorValue: TextFieldValue = TextFieldValue(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val output: List<String> = emptyList(),
    val error: String? = null
)

internal class StudioViewModel : ViewModel() {
    private val connection = LspConnection(viewModelScope)
    private val mutableState = MutableStateFlow(StudioState())
    private var changes: Job? = null
    private var sourceUri: Uri? = null
    val state: StateFlow<StudioState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            connection.notifications.collect { message ->
                when (message["method"]?.jsonPrimitive?.contentOrNull) {
                    "textDocument/publishDiagnostics" -> receiveDiagnostics(message["params"])
                    "window/showMessage" -> appendOutput(
                        message["params"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
            }
        }
    }

    fun setHost(value: String) = mutableState.update { it.copy(host = value) }
    fun setPort(value: String) = mutableState.update { it.copy(port = value.filter(Char::isDigit)) }
    fun select(tab: StudioTab) = mutableState.update { it.copy(tab = tab) }

    fun connect() {
        val current = mutableState.value
        val port = current.port.toIntOrNull() ?: return
        mutableState.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                connection.connect(current.host, port)
                val app = decodeVirtualApp(connection.request("stratum/virtualApp", emptyObject()))
                val subjects = decodeSubjects(connection.request("stratum/documents", emptyObject()))
                mutableState.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        serviceName = app.title,
                        workflow = app.workflow,
                        languages = app.languages,
                        subjects = subjects
                    )
                }
            }.onFailure { failure ->
                mutableState.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        error = failure.message ?: failure::class.simpleName ?: "Connection failed"
                    )
                }
            }
        }
    }

    fun openDocument(context: Context, source: Uri) {
        viewModelScope.launch {
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        source,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                val name = displayName(context, source)
                val text = context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
                    ?: error("Cannot read $name")
                val remoteUri = "file:///android/${URLEncoder.encode(name, UTF_8).replace("+", "%20")}"
                sourceUri = source
                activateDocument(name, remoteUri, text)
            }.onFailure { mutableState.update { state -> state.copy(error = it.message) } }
        }
    }

    fun openRemoteDocument(report: Report) {
        viewModelScope.launch {
            runCatching {
                val source = connection.request("stratum/source", buildJsonObject { put("path", report.path) })
                val path = source.field("path") ?: error("Source is unavailable for ${report.name}")
                val text = source.field("content") ?: error("Source is unavailable for ${report.name}")
                sourceUri = null
                activateDocument(path.substringAfterLast('/'), "file:///$path", text)
            }.onFailure { mutableState.update { state -> state.copy(error = it.message) } }
        }
    }

    fun saveDocument(context: Context) {
        val source = sourceUri ?: return
        val current = mutableState.value
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(source, "wt")?.bufferedWriter()?.use {
                    it.write(current.editorValue.text)
                } ?: error("Cannot write ${current.documentName}")
                current.documentUri?.let { uri ->
                    connection.notify("textDocument/didSave", buildJsonObject {
                        put("textDocument", buildJsonObject { put("uri", uri) })
                    })
                }
                appendOutput("Saved ${current.documentName}")
            }.onFailure { mutableState.update { state -> state.copy(error = it.message) } }
        }
    }

    fun changeDocument(value: TextFieldValue) {
        val textChanged = value.text != mutableState.value.editorValue.text
        mutableState.update { it.copy(editorValue = value) }
        if (!textChanged) return
        changes?.cancel()
        changes = viewModelScope.launch {
            delay(250)
            val current = mutableState.value
            val uri = current.documentUri ?: return@launch
            connection.notify("textDocument/didChange", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri); put("version", 2) })
                put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", current.editorValue.text) }) })
            })
            refreshViews()
        }
    }

    fun format() = withDocument { uri, _ ->
        val edits = connection.request("textDocument/formatting", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put("options", buildJsonObject { put("tabSize", 2); put("insertSpaces", true) })
        }).jsonArray
        edits.firstOrNull()?.jsonObject?.get("newText")?.string()?.let { changeDocument(TextFieldValue(it)) }
    }

    fun evaluate() {
        val current = mutableState.value
        val uri = current.documentUri ?: return
        val (offset, length) = evaluationRange(current.editorValue)
        viewModelScope.launch {
            runCatching {
                val answer = connection.request("stratum/evaluate", buildJsonObject {
                    put("uri", uri)
                    put("offset", offset)
                    put("length", length)
                }).string().orEmpty()
                appendOutput(answer)
                mutableState.update { it.copy(tab = StudioTab.Output) }
            }.onFailure { mutableState.update { state -> state.copy(error = it.message) } }
        }
    }

    private fun withDocument(block: suspend (String, String) -> Unit) {
        val current = mutableState.value
        val uri = current.documentUri ?: return
        viewModelScope.launch {
            runCatching { block(uri, current.editorValue.text) }
                .onFailure { mutableState.update { state -> state.copy(error = it.message) } }
        }
    }

    private suspend fun activateDocument(name: String, uri: String, text: String) {
        mutableState.update {
            it.copy(
                documentName = name,
                documentUri = uri,
                editorValue = TextFieldValue(text),
                tab = StudioTab.Editor,
                error = null
            )
        }
        connection.notify("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("languageId", languageFor(name))
                put("version", 1)
                put("text", text)
            })
        })
        refreshViews()
    }

    private suspend fun refreshViews() {
        val uri = mutableState.value.documentUri ?: return
        val views = connection.request("stratum/views", buildJsonObject { put("uri", uri) })
            .jsonArray.map { entry ->
                WorldView(
                    entry.field("name").orEmpty(),
                    entry.array("items").mapNotNull(JsonElement::string)
                )
            }
        mutableState.update { it.copy(views = views) }
    }

    private fun receiveDiagnostics(params: JsonElement?) {
        val diagnostics = params?.jsonObject?.get("diagnostics")?.jsonArray.orEmpty().map { entry ->
            Diagnostic(
                message = entry.field("message").orEmpty(),
                severity = entry.jsonObject["severity"]?.jsonPrimitive?.intOrNull ?: 1,
                line = entry.jsonObject["range"]?.jsonObject?.get("start")?.jsonObject
                    ?.get("line")?.jsonPrimitive?.intOrNull?.plus(1) ?: 1
            )
        }
        mutableState.update { it.copy(diagnostics = diagnostics) }
    }

    private fun appendOutput(message: String) {
        if (message.isNotBlank()) mutableState.update { it.copy(output = it.output + message) }
    }

    private fun languageFor(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return mutableState.value.languages.firstOrNull { it.equals(extension, ignoreCase = true) }
            ?: extension
    }

    override fun onCleared() {
        viewModelScope.launch { connection.close() }
    }
}

@Composable
private fun StudioApp(
    state: StudioState,
    model: StudioViewModel,
    onOpen: () -> Unit,
    onSave: () -> Unit
) {
    if (!state.connected) {
        ConnectionScreen(state, model)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Scaffold(
            topBar = { StudioBar(state, onOpen, onSave, model::format, model::evaluate) },
            bottomBar = {
                if (!wide) NavigationBar {
                    StudioTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { model.select(tab) },
                            icon = { Icon(tab.icon(), contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail {
                        Spacer(Modifier.height(12.dp))
                        StudioTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = state.tab == tab,
                                onClick = { model.select(tab) },
                                icon = { Icon(tab.icon(), contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                }
                StudioContent(state, model, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConnectionScreen(state: StudioState, model: StudioViewModel) {
    Box(Modifier.fillMaxSize().background(Paper).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().width(420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("STRATUM", color = Teal, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Studio", style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif)
            HorizontalDivider(color = Amber, thickness = 3.dp)
            OutlinedTextField(
                value = state.host,
                onValueChange = model::setHost,
                label = { Text("Service host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = model::setPort,
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = model::connect, enabled = !state.connecting, modifier = Modifier.fillMaxWidth()) {
                if (state.connecting) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.connecting) "Connecting" else "Connect")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioBar(
    state: StudioState,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onFormat: () -> Unit,
    onEvaluate: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(state.serviceName, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                if (state.workflow.isNotEmpty()) {
                    Text(state.workflow.joinToString(" / "), style = MaterialTheme.typography.labelSmall, color = Teal)
                }
            }
        },
        actions = {
            IconButton(onClick = onOpen) { Icon(Icons.Default.FolderOpen, "Open document") }
            IconButton(onClick = onSave, enabled = state.documentUri != null) {
                Icon(Icons.Default.Save, "Save")
            }
            IconButton(onClick = onFormat, enabled = state.documentUri != null) {
                Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Format")
            }
            IconButton(onClick = onEvaluate, enabled = state.documentUri != null) {
                Icon(Icons.Default.PlayArrow, "Evaluate")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
    )
}

@Composable
private fun StudioContent(state: StudioState, model: StudioViewModel, modifier: Modifier = Modifier) {
    when (state.tab) {
        StudioTab.Editor -> EditorPane(state, model::changeDocument, modifier)
        StudioTab.World -> WorldPane(state, model::openRemoteDocument, modifier)
        StudioTab.Output -> OutputPane(state, modifier)
    }
}

@Composable
private fun EditorPane(state: StudioState, onChange: (TextFieldValue) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        Surface(color = Ink, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, contentDescription = null, tint = Amber)
                Spacer(Modifier.width(10.dp))
                Text(state.documentName ?: "No document", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (state.diagnostics.isNotEmpty()) Text("${state.diagnostics.size} issues", color = Amber)
            }
        }
        if (state.documentUri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Open a document to begin", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            BasicTextField(
                value = state.editorValue,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White).padding(16.dp),
                textStyle = TextStyle(color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(Teal)
            )
            if (state.diagnostics.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().height(112.dp).background(Color(0xFFFFF8E7))) {
                    items(state.diagnostics) { diagnostic ->
                        Text(
                            "Line ${diagnostic.line}  ${diagnostic.message}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = if (diagnostic.severity == 1) MaterialTheme.colorScheme.error else Ink,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldPane(state: StudioState, onOpen: (Report) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        state.views.forEach { view ->
            item {
                Text(view.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                view.items.forEach { Text(it, Modifier.padding(top = 7.dp), fontFamily = FontFamily.Monospace) }
            }
        }
        items(state.subjects) { subject ->
            Column {
                Text(subject.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                subject.reports.forEach { report ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(report) }
                            .padding(vertical = 10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = Teal)
                        Spacer(Modifier.width(10.dp))
                        Text(report.name, Modifier.weight(1f))
                        val findings = report.findings + report.hazards
                        if (findings > 0) Text(findings.toString(), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputPane(state: StudioState, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().background(Ink), contentPadding = PaddingValues(16.dp)) {
        if (state.output.isEmpty()) item { Text("No output", color = Color(0xFF9FB2AB), fontFamily = FontFamily.Monospace) }
        items(state.output) { line ->
            Text(line, color = Color(0xFFD9E7E1), fontFamily = FontFamily.Monospace, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun StudioTab.icon() = when (this) {
    StudioTab.Editor -> Icons.Default.Code
    StudioTab.World -> Icons.Default.AccountTree
    StudioTab.Output -> Icons.Default.Terminal
}

private fun emptyObject() = buildJsonObject {}
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.field(name: String): String? = (this as? JsonObject)?.get(name).string()
private fun JsonElement.array(name: String): JsonArray = (this as? JsonObject)?.get(name) as? JsonArray ?: JsonArray(emptyList())

internal fun decodeVirtualApp(element: JsonElement): VirtualApp {
    val name = element.field("name") ?: error("Virtual app name is missing")
    return VirtualApp(
        name = name,
        title = element.field("title") ?: name,
        workflow = element.array("workflow").mapNotNull(JsonElement::string),
        languages = element.array("languages").mapNotNull { it.field("id") }
    )
}

private fun decodeSubjects(element: JsonElement): List<Subject> = element.jsonArray.map { subject ->
    Subject(
        name = subject.field("name").orEmpty(),
        reports = subject.array("reports").map { report ->
            Report(
                name = report.field("name").orEmpty(),
                path = report.field("path").orEmpty(),
                findings = report.jsonObject["findings"]?.jsonPrimitive?.intOrNull ?: 0,
                hazards = report.jsonObject["hazards"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }
    )
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)
    }
    return uri.lastPathSegment ?: "document.txt"
}