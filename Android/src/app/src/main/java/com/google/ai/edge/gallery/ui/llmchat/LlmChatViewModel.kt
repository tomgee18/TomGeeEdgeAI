/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT
import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkLlmResult
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageDocument // Added import
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.Stat
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"
private val STATS = listOf(
  Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
  Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
  Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
  Stat(id = "latency", label = "Latency", unit = "sec")
)

open class LlmChatViewModel(curTask: Task = TASK_LLM_CHAT) : ChatViewModel(task = curTask) {
  fun generateResponse(
    model: Model,
    input: String,
    image: Bitmap? = null,
    documentUri: String? = null, // Added documentUri parameter
    onError: () -> Unit
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      var inputText = input
      val context = getApplication<Context>()

      // Add ChatMessageDocument if URI is present
      if (documentUri != null) {
        val uri = Uri.parse(documentUri)
        val filename = getFileName(context, uri) ?: "Attached Document"
        addMessage(
          model = model,
          message = ChatMessageDocument(
            filename = filename,
            uri = documentUri,
            side = ChatSide.USER, // Assuming document is from user
            accelerator = accelerator
          )
        )

        // Read document content
        val contentResolver = context.contentResolver
        var extractedTextFromDocument: String? = null
        try {
          contentResolver.openInputStream(uri)?.use { inputStream ->
            val mimeType = contentResolver.getType(uri)
            if (mimeType == "application/pdf" || filename.endsWith(".pdf", ignoreCase = true)) {
              // Process PDF
              try {
                // PDDocument's .use block will close the document, which in turn closes the inputStream passed to load()
                PDDocument.load(inputStream).use { document ->
                  val pdfTextStripper = PDFTextStripper()
                  extractedTextFromDocument = pdfTextStripper.getText(document)
                  Log.d(TAG, "Extracted PDF text: $extractedTextFromDocument")
                }
              } catch (e: Exception) {
                Log.e(TAG, "Error reading PDF content: ${e.message}", e)
                addMessage(
                  model = model,
                  message = ChatMessageWarning("Failed to extract text from PDF: $filename. ${e.localizedMessage}")
                )
              }
            } else {
              // Process as plain text
              BufferedReader(InputStreamReader(inputStream)).use { reader ->
                extractedTextFromDocument = reader.readText()
                Log.d(TAG, "Extracted plain text: $extractedTextFromDocument")
              }
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error reading document content: ${e.message}", e)
          addMessage(
            model = model,
            message = ChatMessageWarning("Failed to read content from $filename. ${e.localizedMessage}")
          )
        }

        if (extractedTextFromDocument != null) {
          inputText = "$extractedTextFromDocument\n\n$input"
        }
      }

      setInProgress(true)
      setPreparing(true)

      // Add Loading message *after* the document message (if any)
      addMessage(
        model = model,
        message = ChatMessageLoading(accelerator = accelerator),
      )

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val instance = model.instance as LlmModelInstance
      // Calculate prefill tokens based on the final inputText that includes document content
      var prefillTokens = instance.session.sizeInTokens(inputText)
      if (image != null) {
        // Assuming a fixed token count for an image, this might need adjustment
        // if image tokenization is more dynamic or handled differently by the model.
        prefillTokens += 257
      }

      var firstRun = true
      var timeToFirstToken = 0f
      var firstTokenTs = 0L
      var decodeTokens = 0
      var prefillSpeed = 0f
      var decodeSpeed: Float
      val start = System.currentTimeMillis()

      try {
        LlmChatModelHelper.runInference(model = model,
          input = inputText, // Use potentially modified inputText
          image = image,
          resultListener = { partialResult, done ->
            val curTs = System.currentTimeMillis()

            if (firstRun) {
              firstTokenTs = System.currentTimeMillis()
              timeToFirstToken = (firstTokenTs - start) / 1000f
              prefillSpeed = prefillTokens / timeToFirstToken
              firstRun = false
              setPreparing(false)
            } else {
              decodeTokens++
            }

            // Remove the last message if it is a "loading" message.
            // This will only be done once.
            val lastMessage = getLastMessage(model = model)
            if (lastMessage?.type == ChatMessageType.LOADING) {
              removeLastMessage(model = model)

              // Add an empty message that will receive streaming results.
              addMessage(
                model = model,
                message = ChatMessageText(
                  content = "",
                  side = ChatSide.AGENT,
                  accelerator = accelerator
                )
              )
            }

            // Incrementally update the streamed partial results.
            val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
            updateLastTextMessageContentIncrementally(
              model = model, partialContent = partialResult, latencyMs = latencyMs.toFloat()
            )

            if (done) {
              setInProgress(false)

              decodeSpeed = decodeTokens / ((curTs - firstTokenTs) / 1000f)
              if (decodeSpeed.isNaN()) {
                decodeSpeed = 0f
              }

              if (lastMessage is ChatMessageText) {
                updateLastTextMessageLlmBenchmarkResult(
                  model = model, llmBenchmarkResult = ChatMessageBenchmarkLlmResult(
                    orderedStats = STATS,
                    statValues = mutableMapOf(
                      "prefill_speed" to prefillSpeed,
                      "decode_speed" to decodeSpeed,
                      "time_to_first_token" to timeToFirstToken,
                      "latency" to (curTs - start).toFloat() / 1000f,
                    ),
                    running = false,
                    latencyMs = -1f,
                    accelerator = accelerator,
                  )
                )
              }
            }
          },
          cleanUpListener = {
            setInProgress(false)
            setPreparing(false)
          })
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError()
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      val instance = model.instance as LlmModelInstance
      instance.session.cancelGenerateResponseAsync()
    }
  }

  fun resetSession(model: Model) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      while (true) {
        try {
          LlmChatModelHelper.resetSession(model = model)
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
    }
  }

  fun runAgain(model: Model, message: ChatMessageText, onError: () -> Unit) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model, input = message.content, documentUri = null, onError = onError // Pass null for documentUri here
      )
    }
  }

  fun handleError(
    context: Context,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    triggeredMessage: ChatMessageText,
  ) {
    // Clean up.
    modelManagerViewModel.cleanupModel(task = task, model = model)

    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Remove the last Text message.
    if (getLastMessage(model = model) == triggeredMessage) {
      removeLastMessage(model = model)
    }

    // Add a warning message for re-initializing the session.
    addMessage(
      model = model,
      message = ChatMessageWarning(content = "Error occurred. Re-initializing the session.")
    )

    // Add the triggered message back.
    addMessage(model = model, message = triggeredMessage)

    // Re-initialize the session/engine.
    modelManagerViewModel.initializeModel(
      context = context, task = task, model = model
    )

    // Re-generate the response automatically.
    generateResponse(model = model, input = triggeredMessage.content, documentUri = null, onError = {}) // Pass null for documentUri here
  }
}

class LlmAskImageViewModel : LlmChatViewModel(curTask = TASK_LLM_ASK_IMAGE)

// Helper function to get filename from URI
//SuppressLint is used because the getColumnIndex method might return -1 if the column doesn't exist.
//In a production app, more robust error handling for cursor operations would be advisable.
@SuppressLint("Range")
private fun getFileName(context: Context, uri: Uri): String? {
  var result: String? = null
  if (uri.scheme == "content") {
    val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
    cursor.use {
      if (it != null && it.moveToFirst()) {
        result = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
      }
    }
  }
  if (result == null) {
    result = uri.path
    val cut = result?.lastIndexOf('/')
    if (cut != -1 && cut != null) {
      result = result?.substring(cut + 1)
    }
  }
  return result
}