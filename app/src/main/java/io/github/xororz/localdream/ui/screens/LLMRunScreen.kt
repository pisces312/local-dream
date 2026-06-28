package io.github.xororz.localdream.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.LLMModel
import io.github.xororz.localdream.data.LLMModelRepository
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.LLMHttpClient
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: String, // "user" or "assistant"
    val content: String,
    val isStreaming: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMRunScreen(
    modelId: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val llmRepository = remember { LLMModelRepository.getInstance(context) }

    LaunchedEffect(Unit) { llmRepository.ensureLoaded() }

    val model = remember(llmRepository.models) {
        llmRepository.models.find { it.id == modelId }
    }

    val llmState by BackendService.llmBackendState.collectAsState()
    val servingLlmId by BackendService.servingLlmModelId.collectAsState()

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var lastAssistantMessage by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Start backend on enter
    LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        if (servingLlmId == model.id && llmState is BackendService.BackendState.Running) {
            return@LaunchedEffect
        }

        isLoading = true
        startLlmBackend(context, model)
        isLoading = false
    }

    // Monitor backend state
    LaunchedEffect(llmState) {
        when (llmState) {
            is BackendService.BackendState.Running -> {
                isLoading = false
            }
            is BackendService.BackendState.Error -> {
                isLoading = false
                isGenerating = false
            }
            else -> {}
        }
    }

    // Stop backend on leave
    DisposableEffect(Unit) {
        onDispose {
            if (servingLlmId == modelId) {
                stopLlmBackend(context)
            }
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || isGenerating) return

        inputText = ""
        messages = messages + ChatMessage("user", text)
        messages = messages + ChatMessage("assistant", "", isStreaming = true)
        isGenerating = true

        scope.launch {
            val result = LLMHttpClient.chat(text) { token ->
                messages = messages.toMutableList().apply {
                    val lastIndex = indices.last
                    set(lastIndex, get(lastIndex).copy(
                        content = get(lastIndex).content + token
                    ))
                }
            }

            isGenerating = false
            result.onSuccess { fullText ->
                lastAssistantMessage = fullText
                messages = messages.toMutableList().apply {
                    val lastIndex = indices.last
                    set(lastIndex, get(lastIndex).copy(
                        content = fullText,
                        isStreaming = false
                    ))
                }
            }.onFailure { e ->
                messages = messages.toMutableList().apply {
                    val lastIndex = indices.last
                    set(lastIndex, get(lastIndex).copy(
                        content = context.getString(R.string.llm_error, e.message),
                        isStreaming = false
                    ))
                }
            }
        }
    }

    fun resetChat() {
        messages = emptyList()
        lastAssistantMessage = ""
        scope.launch {
            LLMHttpClient.reset()
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", text))
        Toast.makeText(context, context.getString(R.string.llm_copy_prompt), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(model?.name ?: modelId) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Reset chat
                    IconButton(onClick = { resetChat() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.llm_reset_chat))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.llm_loading))
                    }
                }
            } else if (llmState is BackendService.BackendState.Error) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.llm_error, (llmState as BackendService.BackendState.Error).message),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                // Chat messages
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(messages) { msg ->
                        ChatBubble(
                            message = msg,
                            onCopy = { copyToClipboard(msg.content) },
                        )
                    }
                }

                // Action buttons (show when assistant has a message)
                if (lastAssistantMessage.isNotEmpty() && !isGenerating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Generate Image button - copy prompt and go to model list
                        Button(
                            onClick = {
                                // Copy prompt to clipboard
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("prompt", lastAssistantMessage))
                                Toast.makeText(context, context.getString(R.string.llm_copy_prompt), Toast.LENGTH_SHORT).show()
                                // Navigate back to model list so user can pick an SD model
                                navController.popBackStack(Screen.ModelList.route, false)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.llm_generate_image))
                        }

                        // Copy button
                        OutlinedButton(
                            onClick = { copyToClipboard(lastAssistantMessage) },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        }
                    }
                }

                // Input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.llm_input_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                        maxLines = 4,
                        enabled = !isGenerating,
                    )

                    IconButton(
                        onClick = { sendMessage() },
                        enabled = inputText.isNotBlank() && !isGenerating,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.llm_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(12.dp)
        ) {
            if (message.isStreaming && message.content.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (!isUser && message.content.isNotEmpty() && !message.isStreaming) {
            TextButton(onClick = onCopy, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.llm_copy_prompt), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun startLlmBackend(context: Context, model: LLMModel) {
    // Determine HTP config based on SoC
    val socModel = Build.SOC_MODEL
    val htpConfig = when {
        socModel.startsWith("SM8850") || socModel.startsWith("SM8750") ||
            socModel == "SM8750P" || socModel == "SM8850P" || socModel == "SM8845" ->
            "qualcomm-snapdragon-8-elite.json"
        socModel.startsWith("SM8650") -> "qualcomm-snapdragon-8-gen3.json"
        socModel.startsWith("QCS8550") -> "qualcomm-snapdragon-8-gen2.json"
        else -> "qualcomm-snapdragon-8-elite.json" // default fallback
    }

    val intent = Intent(context, BackendService::class.java).apply {
        putExtra("modelType", "llm")
        putExtra("modelId", model.id)
        putExtra("modelDir", model.modelDir)
        putExtra("htpConfig", htpConfig)
    }
    context.startForegroundService(intent)
}

private fun stopLlmBackend(context: Context) {
    val intent = Intent(context, BackendService::class.java).apply {
        action = BackendService.ACTION_STOP
    }
    context.startService(intent)
}
