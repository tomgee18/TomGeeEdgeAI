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

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.serialization.Serializable

/** Navigation destination data */
object LlmChatDestination {
  @Serializable
  val route = "LlmChatRoute"
}

object LlmAskImageDestination {
  @Serializable
  val route = "LlmAskImageRoute"
}

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmChatViewModel = viewModel(
    factory = ViewModelProvider.Factory
  ),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    navigateUp = navigateUp,
    modifier = modifier,
  )
}

@Composable
fun LlmAskImageScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskImageViewModel = viewModel(
    factory = ViewModelProvider.Factory
  ),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    navigateUp = navigateUp,
    modifier = modifier,
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var selectedDocumentUri by remember { mutableStateOf<Uri?>(null) }

  // Launcher for document selection
  val selectDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) {
      selectedDocumentUri = uri
      // Note: The ChatMessageDocument is now created in LlmChatViewModel.
      // If a visual confirmation of selection is needed immediately in UI before sending,
      // that logic would be here or passed down.
    }
  }

  val onLaunchDocumentPicker = {
    selectDocumentLauncher.launch(arrayOf("*/*"))
  }

  ChatView(
    task = viewModel.task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    // onSendMessage now takes the selectedDocumentUri directly from this composable's state
    onSendMessage = { model, messages -> // documentUri removed from here, will use state
      // The ChatMessageDocument is created in the ViewModel.
      // Here, we just pass the URI along with the main message content.

      // Add user's typed messages (text/image)
      for (message in messages) {
        viewModel.addMessage( // This adds the typed text/image messages
          model = model,
          message = message,
        )
      }

      var text = "" // User typed text
      var image: Bitmap? = null // User picked image
      var chatMessageText: ChatMessageText? = null

      // Extract text and image from the messages list from MessageInputText
      messages.forEach { msg ->
        when (msg) {
          is ChatMessageText -> {
            chatMessageText = msg
            text = msg.content
          }
          is ChatMessageImage -> image = msg.bitmap
          else -> Unit // Ignore other types here, like ChatMessageDocument
        }
      }

      // Ensure there's text content OR a document to send.
      // The ViewModel will handle adding ChatMessageDocument based on selectedDocumentUri.
      // If only a document is selected with no text, the ViewModel should still process it.
      if (text.isNotEmpty() || selectedDocumentUri != null) {
        if (text.isNotEmpty()) { // Add text input to history only if text is present
            modelManagerViewModel.addTextInputHistory(text)
        }
        viewModel.generateResponse(
          model = model,
          input = text, // This is the user-typed text
          image = image, // This is the user-picked image
          documentUri = selectedDocumentUri?.toString(), // Pass the stored URI
          onError = {
            viewModel.handleError(
              context = context,
              model = model,
              modelManagerViewModel = modelManagerViewModel,
              triggeredMessage = chatMessageText,
            )
          })
        selectedDocumentUri = null // Reset URI after sending
      }
    },
    // Pass the launcher trigger down.
    // ChatView, ChatPanel, and MessageInputText will need to be updated to accept this.
    onLaunchDocumentPicker = onLaunchDocumentPicker,
    // selectedDocumentUri can be passed down if MessageInputText needs to display its name or allow clearing it.
    // For now, keeping it simple as per primary goal of *sending* it.
    // selectedDocumentUri = selectedDocumentUri,
    // onClearDocument = { selectedDocumentUri = null }
    onRunAgainClicked = { model, message ->
      if (message is ChatMessageText) {
        viewModel.runAgain(model = model, message = message, onError = {
          viewModel.handleError(
            context = context,
            model = model,
            modelManagerViewModel = modelManagerViewModel,
            triggeredMessage = message,
          )
        })
      }
    },
    onBenchmarkClicked = { _, _, _, _ ->
    },
    onResetSessionClicked = { model ->
      viewModel.resetSession(model = model)
    },
    showStopButtonInInputWhenInProgress = true,
    onStopButtonClicked = { model ->
      viewModel.stopResponse(model = model)
    },
    navigateUp = navigateUp,
    modifier = modifier,
  )
}