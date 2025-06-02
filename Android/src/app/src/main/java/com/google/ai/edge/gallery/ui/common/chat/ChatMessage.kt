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

package com.google.ai.edge.gallery.ui.common.chat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import com.google.ai.edge.gallery.data.Model
import java.util.UUID

enum class ChatMessageType {
  INFO,
  WARNING,
  TEXT,
  IMAGE,
  IMAGE_WITH_HISTORY,
  LOADING,
  CLASSIFICATION,
  CONFIG_VALUES_CHANGE,
  BENCHMARK_RESULT,
  BENCHMARK_LLM_RESULT,
  PROMPT_TEMPLATES,
  DOCUMENT // Added DOCUMENT type
}

enum class ChatSide {
  USER, AGENT, SYSTEM
}

data class Classification(val label: String, val score: Float, val color: Color)

/** Base class for a chat message. */
open class ChatMessage(
  open val type: ChatMessageType,
  open val side: ChatSide,
  open val latencyMs: Float = -1f,
  open val accelerator: String = "",
  // Adding id and timestamp to base for ChatMessageDocument,
  // though they might not be used by all existing ChatMessage types.
  // Alternatively, ChatMessageDocument could not inherit these if that's cleaner.
  // For now, adding them here to match the provided data class structure.
  open val id: String = UUID.randomUUID().toString(),
  open val timestamp: Long = System.currentTimeMillis(),
  // Adding stats for ChatMessageDocument, similar reasoning as above.
  open val stats: ChatMessageStats? = null,

) {
  open fun clone(): ChatMessage {
    return ChatMessage(type = type, side = side, latencyMs = latencyMs, id = id, timestamp = timestamp, stats = stats)
  }
}

// Placeholder for ChatMessageStats if it's not defined elsewhere
// data class ChatMessageStats(val someStat: String)
// Remove this if ChatMessageStats is already defined.
// Based on the prompt, ChatMessageDocument has llmBenchmarkResult, but ChatMessage base doesn't.
// This implies ChatMessageDocument might need its own llmBenchmarkResult or the base class needs it.
// For now, aligning with the prompt for ChatMessageDocument.

/** Chat message for showing loading status. */
class ChatMessageLoading(override val accelerator: String = "") :
  ChatMessage(type = ChatMessageType.LOADING, side = ChatSide.AGENT, accelerator = accelerator)

/** Chat message for info (help). */
class ChatMessageInfo(val content: String) :
  ChatMessage(type = ChatMessageType.INFO, side = ChatSide.SYSTEM)

/** Chat message for info (help). */
class ChatMessageWarning(val content: String) :
  ChatMessage(type = ChatMessageType.WARNING, side = ChatSide.SYSTEM)

/** Chat message for config values change. */
class ChatMessageConfigValuesChange(
  val model: Model,
  val oldValues: Map<String, Any>,
  val newValues: Map<String, Any>
) : ChatMessage(type = ChatMessageType.CONFIG_VALUES_CHANGE, side = ChatSide.SYSTEM)

/** Chat message for plain text. */
open class ChatMessageText(
  val content: String,
  override val side: ChatSide,
  // Negative numbers will hide the latency display.
  override val latencyMs: Float = 0f,
  val isMarkdown: Boolean = true,

  // Benchmark result for LLM response.
  var llmBenchmarkResult: ChatMessageBenchmarkLlmResult? = null,
  override val accelerator: String = "",
) : ChatMessage(
  type = ChatMessageType.TEXT,
  side = side,
  latencyMs = latencyMs,
  accelerator = accelerator
) {
  override fun clone(): ChatMessageText {
    return ChatMessageText(
      content = content,
      side = side,
      latencyMs = latencyMs,
      accelerator = accelerator,
      isMarkdown = isMarkdown,
      llmBenchmarkResult = llmBenchmarkResult,
    )
  }
}

/** Chat message for images. */
class ChatMessageImage(
  val bitmap: Bitmap,
  val imageBitMap: ImageBitmap,
  override val side: ChatSide,
  override val latencyMs: Float = 0f
) :
  ChatMessage(type = ChatMessageType.IMAGE, side = side, latencyMs = latencyMs) {
  override fun clone(): ChatMessageImage {
    return ChatMessageImage(
      bitmap = bitmap,
      imageBitMap = imageBitMap,
      side = side,
      latencyMs = latencyMs
    )
  }
}

/** Chat message for images with history. */
class ChatMessageImageWithHistory(
  val bitmaps: List<Bitmap>,
  val imageBitMaps: List<ImageBitmap>,
  val totalIterations: Int,
  override val side: ChatSide,
  override val latencyMs: Float = 0f,
  var curIteration: Int = 0, // 0-based
) :
  ChatMessage(type = ChatMessageType.IMAGE_WITH_HISTORY, side = side, latencyMs = latencyMs) {
  fun isRunning(): Boolean {
    return curIteration < totalIterations - 1
  }
}

/** Chat message for showing classification result. */
class ChatMessageClassification(
  val classifications: List<Classification>,
  override val latencyMs: Float = 0f,
  // Typical android phone width is > 320dp
  val maxBarWidth: Dp? = null,
) : ChatMessage(type = ChatMessageType.CLASSIFICATION, side = ChatSide.AGENT, latencyMs = latencyMs)

/** A stat used in benchmark result. */
data class Stat(val id: String, val label: String, val unit: String)

/** Chat message for showing benchmark result. */
class ChatMessageBenchmarkResult(
  val orderedStats: List<Stat>,
  val statValues: MutableMap<String, Float>,
  val values: List<Float>,
  val histogram: Histogram,
  val warmupCurrent: Int,
  val warmupTotal: Int,
  val iterationCurrent: Int,
  val iterationTotal: Int,
  override val latencyMs: Float = 0f,
  val highlightStat: String = "",
) :
  ChatMessage(
    type = ChatMessageType.BENCHMARK_RESULT,
    side = ChatSide.AGENT,
    latencyMs = latencyMs
  ) {
  fun isWarmingUp(): Boolean {
    return warmupCurrent < warmupTotal
  }

  fun isRunning(): Boolean {
    return iterationCurrent < iterationTotal
  }
}

/** Chat message for showing LLM benchmark result. */
class ChatMessageBenchmarkLlmResult(
  val orderedStats: List<Stat>,
  val statValues: MutableMap<String, Float>,
  val running: Boolean,
  override val latencyMs: Float = 0f,
  override val accelerator: String = "",
) : ChatMessage(
  type = ChatMessageType.BENCHMARK_LLM_RESULT,
  side = ChatSide.AGENT,
  latencyMs = latencyMs,
  accelerator = accelerator,
)

data class Histogram(
  val buckets: List<Int>,
  val maxCount: Int,
  val highlightBucketIndex: Int = -1
)

/** Chat message for showing prompt templates. */
class ChatMessagePromptTemplates(
  val templates: List<PromptTemplate>,
  val showMakeYourOwn: Boolean = true,
) : ChatMessage(type = ChatMessageType.PROMPT_TEMPLATES, side = ChatSide.SYSTEM)

data class PromptTemplate(val title: String, val description: String, val prompt: String)

// Define ChatMessageStats if it's not defined. Assuming it's a placeholder based on the prompt.
// If ChatMessageStats is a real class elsewhere, this might not be needed.
// For the purpose of this diff, I'm assuming it needs to be defined or already exists.
// Let's assume it might be a typealias or a simple data class for now if not complex.
typealias ChatMessageStats = Map<String, String> // Example placeholder

data class ChatMessageDocument(
  val filename: String,
  val uri: String, // Store the URI for potential future use (e.g., opening the doc)
  override val id: String = UUID.randomUUID().toString(),
  override val side: ChatSide = ChatSide.USER, // Or determine based on context
  override val type: ChatMessageType = ChatMessageType.DOCUMENT,
  override val timestamp: Long = System.currentTimeMillis(),
  override val stats: ChatMessageStats? = null,
  val llmBenchmarkResult: ChatMessageBenchmarkLlmResult? = null, // Specific to DOCUMENT or general?
  override val accelerator: String? = null,
) : ChatMessage(
  type = type,
  side = side,
  id = id,
  timestamp = timestamp,
  stats = stats,
  accelerator = accelerator ?: ""
  // latencyMs is not in ChatMessageDocument per prompt, but is in base ChatMessage.
  // This will use the default latencyMs from the base class.
)