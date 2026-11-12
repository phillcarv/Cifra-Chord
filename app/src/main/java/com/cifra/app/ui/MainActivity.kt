package com.cifra.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cifra.app.audio.AudioRecorder
import com.cifra.app.network.ApiClient
import com.cifra.app.network.CifraLine
import com.cifra.app.network.CifraResponse
import com.cifra.app.network.TrackInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

// -------------------------------------------------------------
// 1. Algoritmo de Transposição Harmônica em Tempo Real
// -------------------------------------------------------------
object ChordTransposer {
    private val NOTES_SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val NOTES_FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    fun transposeChord(chord: String, semitones: Int): String {
        if (chord.isBlank() || semitones == 0) return chord
        val regex = Regex("^([A-G][#b]?)(.*)$")
        val match = regex.find(chord.trim()) ?: return chord
        val root = match.groupValues[1]
        val suffix = match.groupValues[2]

        val indexSharp = NOTES_SHARP.indexOf(root)
        val indexFlat = NOTES_FLAT.indexOf(root)
        val currentIndex = if (indexSharp != -1) indexSharp else indexFlat
        if (currentIndex == -1) return chord

        val newIndex = (currentIndex + semitones).mod(12)
        return NOTES_SHARP[newIndex] + suffix
    }

    fun transposeChordsLine(line: String, semitones: Int): String {
        if (semitones == 0 || line.isBlank()) return line
        val chordRegex = Regex("\\b[A-G][#b]?(?:m|maj|min|dim|aug|sus[24]?|[0-9]+)*(?:/[A-G][#b]?)?\\b")
        return chordRegex.replace(line) { matchResult ->
            val fullChord = matchResult.value
            if (fullChord.contains("/")) {
                val parts = fullChord.split("/")
                val transposedRoot = transposeChord(parts[0], semitones)
                val transposedBass = transposeChord(parts[1], semitones)
                "$transposedRoot/$transposedBass"
            } else {
                transposeChord(fullChord, semitones)
            }
        }
    }
}

// -------------------------------------------------------------
// 2. Modelo de Persistência Offline (Repertório do Músico)
// -------------------------------------------------------------
data class SavedSong(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val key: String?,
    val bpm: Float?,
    val lines: List<CifraLine>,
    val formattedCifra: String
)

object OfflineRepertoireManager {
    private const val FILE_NAME = "repertoire.json"

    fun load(context: Context): List<SavedSong> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            val json = file.readText()
            val type = object : TypeToken<List<SavedSong>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, song: SavedSong) {
        val currentList = load(context).toMutableList()
        currentList.removeAll { it.title.equals(song.title, ignoreCase = true) }
        currentList.add(0, song)
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(Gson().toJson(currentList))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE94560),
                    secondary = Color(0xFF0F3460),
                    background = Color(0xFF16213E),
                    surface = Color(0xFF1A1A2E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CifraAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CifraAppScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val audioRecorder = remember { AudioRecorder(context) }
    val listState = rememberLazyListState()

    var isRecording by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Pronto para ouvir ou carregar música.") }
    var cifraResult by remember { mutableStateOf<CifraResponse?>(null) }
    var serverUrl by remember { mutableStateOf(ApiClient.baseUrl) }

    // Recursos para Músicos
    var transposeSemitones by remember { mutableIntStateOf(0) }
    var isAutoScrolling by remember { mutableStateOf(false) }
    var scrollSpeed by remember { mutableFloatStateOf(1.0f) }

    // Monetização e Freemium
    var freeCredits by remember { mutableIntStateOf(3) }
    var isProUser by remember { mutableStateOf(false) }
    var showPaywallDialog by remember { mutableStateOf(false) }
    var showRepertoireDrawer by remember { mutableStateOf(false) }
    var savedRepertoire by remember { mutableStateOf(OfflineRepertoireManager.load(context)) }

    // Rolagem Automática Suave no Palco
    LaunchedEffect(isAutoScrolling, scrollSpeed) {
        while (isAutoScrolling) {
            listState.scrollBy(2.5f * scrollSpeed)
            delay(40)
        }
    }

    // Processamento com Verificação Freemium
    fun sendAudioFile(file: File) {
        if (!isProUser && freeCredits <= 0) {
            showPaywallDialog = true
            return
        }

        isLoading = true
        transposeSemitones = 0
        statusMessage = "Analisando áudio, identificando acordes e transcrevendo..."
        ApiClient.baseUrl = serverUrl

        coroutineScope.launch {
            try {
                val requestBody = file.asRequestBody("audio/*".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", file.name, requestBody)
                val response = ApiClient.getService().processAudio(part)
                cifraResult = response
                if (!isProUser) freeCredits--
                statusMessage = "Cifra processada com sucesso! ${if (!isProUser) "($freeCredits créditos grátis restantes)" else "Plano Pro Ativo"}"
            } catch (e: Exception) {
                statusMessage = "Erro ao conectar com servidor: ${e.localizedMessage}"
                Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = copyUriToFile(context, it)
            if (file != null) sendAudioFile(file)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            audioRecorder.startRecording()
            isRecording = true
            statusMessage = "Ouvindo música pelo microfone..."
        } else {
            Toast.makeText(context, "Permissão de microfone necessária", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cifra Chord 🎸", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Color.White
                ),
                actions = {
                    // Botão Repertório Offline
                    IconButton(onClick = {
                        savedRepertoire = OfflineRepertoireManager.load(context)
                        showRepertoireDrawer = true
                    }) {
                        Icon(Icons.Default.Folder, contentDescription = "Repertório Salvo", tint = Color(0xFFFFD166))
                    }
                    // Botão Pro / Monetização
                    TextButton(onClick = { showPaywallDialog = true }) {
                        Text(if (isProUser) "PRO ⭐" else "ASSINAR PRO", color = Color(0xFFFFD166), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Controles de Entrada (Microfone / Upload)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (isRecording) {
                            val recordedFile = audioRecorder.stopRecording()
                            isRecording = false
                            if (recordedFile != null) sendAudioFile(recordedFile)
                        } else {
                            val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                audioRecorder.startRecording()
                                isRecording = true
                                statusMessage = "Ouvindo música..."
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRecording) "Parar" else "Ouvir no Bar")
                }

                Button(
                    onClick = { filePickerLauncher.launch("audio/*") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Carregar MP3")
                }
            }

            Spacer(Modifier.height(10.dp))

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            Text(text = statusMessage, color = Color.LightGray, fontSize = 12.sp)

            Spacer(Modifier.height(10.dp))

            // Barra de Ferramentas do Show (Transposição e Auto-Scroll)
            cifraResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Transpor Tom
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tom: ", fontSize = 13.sp, color = Color.White)
                            IconButton(onClick = { transposeSemitones-- }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Remove, contentDescription = "Descer 1/2 tom", tint = Color.White)
                            }
                            Text(
                                text = if (transposeSemitones >= 0) "+$transposeSemitones" else "$transposeSemitones",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD166),
                                fontSize = 14.sp
                            )
                            IconButton(onClick = { transposeSemitones++ }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Subir 1/2 tom", tint = Color.White)
                            }
                        }

                        // Auto-Scroll no Palco
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { isAutoScrolling = !isAutoScrolling },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAutoScrolling) Color(0xFF2E7D32) else Color.DarkGray
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(if (isAutoScrolling) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isAutoScrolling) "Pausar" else "Rolar Tela", fontSize = 12.sp)
                            }
                        }

                        // Salvar Offline no Repertório
                        IconButton(onClick = {
                            val songToSave = SavedSong(
                                title = result.trackInfo.title ?: "Cifra ${System.currentTimeMillis() % 1000}",
                                key = result.detectedKey,
                                bpm = result.bpm,
                                lines = result.lines,
                                formattedCifra = result.formattedCifra
                            )
                            OfflineRepertoireManager.save(context, songToSave)
                            Toast.makeText(context, "Música salva no Repertório Offline!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = "Salvar Offline", tint = Color.White)
                        }
                    }
                }

                // Visualizador da Cifra
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = result.trackInfo.title ?: "Música Identificada",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "Tom Original: ${result.detectedKey ?: "G"} | BPM: ${result.bpm ?: 120}",
                                    fontSize = 13.sp,
                                    color = Color(0xFFFFD166)
                                )
                            }
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(result.formattedCifra))
                                Toast.makeText(context, "Cifra copiada para a área de transferência!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = Color.White)
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)

                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(result.lines) { line: CifraLine ->
                                val transposedChords = ChordTransposer.transposeChordsLine(line.chordsLine, transposeSemitones)
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = transposedChords,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF6B6B),
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = line.lyricsLine,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal de Paywall e Planos
    if (showPaywallDialog) {
        AlertDialog(
            onDismissRequest = { showPaywallDialog = false },
            title = { Text("Desbloqueie o Modo Show Pro 🎸", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cifre músicas ilimitadas na hora do show sem interrupções:", fontSize = 14.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3460))) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Plano Mensal Barzinho", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("R$ 19,90 / mês", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFD166))
                            Text("Cancele quando quiser.", fontSize = 12.sp, color = Color.LightGray)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E))) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Plano Anual (Economize 37%)", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("R$ 149,90 / ano", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF4ECCA3))
                            Text("Apenas R$ 12,49 por mês.", fontSize = 12.sp, color = Color.LightGray)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    isProUser = true
                    showPaywallDialog = false
                    Toast.makeText(context, "Plano Pro ativado com sucesso!", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Ativar Assinatura Pro")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaywallDialog = false }) {
                    Text("Continuar Grátis ($freeCredits restantes)")
                }
            }
        )
    }

    // Modal de Repertório Offline Salvo
    if (showRepertoireDrawer) {
        AlertDialog(
            onDismissRequest = { showRepertoireDrawer = false },
            title = { Text("📁 Repertório Offline Salvo") },
            text = {
                if (savedRepertoire.isEmpty()) {
                    Text("Nenhuma música salva ainda. Toque no ícone de marcador para salvar suas cifras para os shows.")
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(savedRepertoire) { song ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(song.title, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("Tom: ${song.key ?: "G"} | BPM: ${song.bpm ?: 120}", fontSize = 12.sp, color = Color.LightGray)
                                    }
                                    Button(onClick = {
                                        cifraResult = CifraResponse(
                                            trackInfo = TrackInfo(true, song.title, null, null, null),
                                            durationSeconds = 180f,
                                            detectedKey = song.key,
                                            bpm = song.bpm,
                                            chords = emptyList(),
                                            formattedCifra = song.formattedCifra,
                                            lines = song.lines
                                        )
                                        showRepertoireDrawer = false
                                    }) {
                                        Text("Abrir")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRepertoireDrawer = false }) {
                    Text("Fechar")
                }
            }
        )
    }
}
