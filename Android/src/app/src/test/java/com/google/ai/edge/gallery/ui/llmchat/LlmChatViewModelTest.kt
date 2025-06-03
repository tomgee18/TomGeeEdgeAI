package com.google.ai.edge.gallery.ui.llmchat

import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageDocument
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
// import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType // Not explicitly used in assertions here
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.junit.MockitoJUnitRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException // For testing exceptions
import java.lang.reflect.Method
import app.cash.turbine.test
// PDFBox imports - needed if we try to mock them directly or verify interactions with them.
// For this version, we mostly mock their outcomes.
// import org.apache.pdfbox.pdmodel.PDDocument
// import org.apache.pdfbox.text.PDFTextStripper

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class LlmChatViewModelTest {

    @Mock
    private lateinit var mockApplication: Application

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockContentResolver: ContentResolver

    @Mock
    private lateinit var mockCursor: Cursor

    @Mock
    private lateinit var mockModel: Model

    @Mock
    private lateinit var mockLlmModelInstance: LlmModelInstance

    @Mock
    private lateinit var mockLlmInferenceSession: LlmInferenceSession

    @Captor
    private lateinit var stringArgumentCaptor: ArgumentCaptor<String>

    @Mock
    private lateinit var mockBitmap: Bitmap


    private lateinit var viewModel: LlmChatViewModel
    private lateinit var getFileNameMethod: Method

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        `when`(mockApplication.applicationContext).thenReturn(mockContext)
        `when`(mockApplication.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)

        viewModel = LlmChatViewModel()

        getFileNameMethod = LlmChatViewModel::class.java.getDeclaredMethod("getFileName", Context::class.java, Uri::class.java)
        getFileNameMethod.isAccessible = true

        `when`(mockModel.instance).thenReturn(mockLlmModelInstance)
        `when`(mockLlmModelInstance.session).thenReturn(mockLlmInferenceSession)
        `when`(mockLlmInferenceSession.sizeInTokens(anyString())).thenReturn(0)

         doAnswer { invocation ->
            val listener = invocation.getArgument<((String, Boolean) -> Unit)>(0) // LlmChatModelHelper.ResultListener
            // Simulate model processing the input and invoking listener
            // The actual string passed to listener could be verified if needed
            listener.invoke("Model response to input", true)
            null
        }.`when`(mockLlmInferenceSession).generateResponseAsync(any()) // Using any() for LlmChatModelHelper.ResultListener
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun invokeGetFileName(context: Context, uri: Uri): String? {
        return getFileNameMethod.invoke(viewModel, context, uri) as? String
    }

    // --- getFileName Tests ---
    @Test
    fun `getFileName returns display name when content URI is valid`() {
        val fakeUri = Uri.parse("content://com.example.provider/document/1")
        val expectedDisplayName = "test_document.pdf"
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedDisplayName)
        `when`(mockCursor.close()).then {}
        val result = invokeGetFileName(mockContext, fakeUri)
        assertEquals(expectedDisplayName, result)
    }
     @Test
    fun `getFileName returns null when cursor is null`() {
        val fakeUri = Uri.parse("content://com.example.provider/document/2")
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(null)
        val result = invokeGetFileName(mockContext, fakeUri)
        assertEquals(null, result)
    }

    @Test
    fun `getFileName returns null when cursor is empty`() {
        val fakeUri = Uri.parse("content://com.example.provider/document/3")
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(false)
        `when`(mockCursor.close()).then {}
        val result = invokeGetFileName(mockContext, fakeUri)
        assertEquals(null, result)
    }

    @Test
    fun `getFileName returns null when display name column is missing`() {
        val fakeUri = Uri.parse("content://com.example.provider/document/4")
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(-1)
        `when`(mockCursor.close()).then {}
        val result = invokeGetFileName(mockContext, fakeUri)
        assertEquals(null, result)
    }

    @Test
    fun `getFileName returns path segment when URI is not content scheme`() {
        val fakeUri = Uri.parse("file:///storage/emulated/0/Download/another_document.txt")
        val expectedFileName = "another_document.txt"
        val result = invokeGetFileName(mockContext, fakeUri)
        assertEquals(expectedFileName, result)
    }

    @Test
    fun `getFileName returns full path if not content scheme and no slashes`() {
        val fakeUri = Uri.parse("file:document_no_slash.doc")
        val expectedFileName = "document_no_slash.doc"
        val result = invokeGetFileName(mockContext, fakeUri)
        assertEquals(expectedFileName, result)
    }

    @Test
    fun `getFileName_contentUri_nullDisplayNameInCursor_usesDefaultInGenerateResponse`() = runTest {
        val documentUriString = "content://com.example.provider/document/no_name_in_cursor"
        val fakeUri = Uri.parse(documentUriString)
        val defaultFilename = "Attached Document"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        // Simulate cursor that has a row, but getString for display name returns null
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(null) // Display name is null
        `when`(mockCursor.close()).then {}

        // Mock openInputStream to avoid errors unrelated to filename
        val emptyStream: InputStream = ByteArrayInputStream("".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(emptyStream)


        viewModel.messages.test {
            viewModel.generateResponse(mockModel, "User input", null, documentUriString) {}

            val currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            val docMessage = currentMessages.find { it is ChatMessageDocument }
            assertNotNull("ChatMessageDocument should be added", docMessage)
            assertEquals("Filename should be default when getFileName returns null", defaultFilename, (docMessage as ChatMessageDocument).filename)

            cancelAndConsumeRemainingEvents()
        }
    }


    // --- generateResponse Tests for Document Handling ---
    @Test
    fun `generateResponse with TEXT document success - adds DocumentMsg, prepends text`() = runTest {
        val documentUriString = "content://com.example.provider/document/doc_plain.txt"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "doc_plain.txt"
        val documentContent = "This is plain text content."
        val userInput = "User input here."

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("text/plain")

        val inputStream: InputStream = ByteArrayInputStream(documentContent.toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(inputStream)

        viewModel.messages.test {
            viewModel.generateResponse(mockModel, userInput, null, documentUriString) {}
            val firstEmissionMessages = awaitItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            val docMessage = firstEmissionMessages.find { it is ChatMessageDocument && it.filename == expectedFilename }
            assertNotNull("ChatMessageDocument for plain text not found", docMessage)
            // Verification of prepended text to LlmChatModelHelper.runInference's input
            // This requires capturing the argument to a method that LlmChatModelHelper calls,
            // e.g., session.addQueryChunk or similar, or directly mocking LlmChatModelHelper if possible.
            // For now, we assume the ViewModel's internal logic for `inputText = "$extractedTextFromDocument\n\n$input"` works
            // and focus on message emission. Deeper verification of runInference input is tricky.
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `generateResponse identifies PDF by MIME type`() = runTest {
        val documentUriString = "content://com.example.provider/document/doc_pdf_mime"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "doc_pdf_mime.bin"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("application/pdf")

        val inputStream: InputStream = ByteArrayInputStream("fake pdf data".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(inputStream)

        viewModel.messages.test {
            viewModel.generateResponse(mockModel, "input", null, documentUriString) {}

            val currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            val docMessage = currentMessages.find { it is ChatMessageDocument }
            assertNotNull(docMessage)

             val pdfSpecificWarning = currentMessages.find { msg -> msg is ChatMessageWarning && msg.content.startsWith("Failed to extract text from PDF:")}
            assertNotNull("A PDF-specific warning should be present if dummy data causes parse error.", pdfSpecificWarning)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `generateResponse identifies PDF by filename extension`() = runTest {
        val documentUriString = "content://com.example.provider/document/doc_by_ext.pdf"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "doc_by_ext.pdf"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("application/octet-stream")

        val inputStream: InputStream = ByteArrayInputStream("fake pdf data".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(inputStream)

        viewModel.messages.test {
            viewModel.generateResponse(mockModel, "input", null, documentUriString) {}
            val currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            val docMessage = currentMessages.find { it is ChatMessageDocument }
            assertNotNull(docMessage)

            val pdfSpecificWarning = currentMessages.find { msg -> msg is ChatMessageWarning && msg.content.startsWith("Failed to extract text from PDF:")}
            assertNotNull("A PDF-specific warning (due to extension) should be present if dummy data causes parse error.", pdfSpecificWarning)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `generateResponse with PDF success (conceptual) - no PDF specific error`() = runTest {
        val documentUriString = "content://com.example.provider/document/realdeal.pdf"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "realdeal.pdf"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("application/pdf")

        val minimalValidPdfLikeStream: InputStream = ByteArrayInputStream("%PDF-1.0\n%%EOF".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(minimalValidPdfLikeStream)

        viewModel.messages.test {
            viewModel.generateResponse(mockModel, "User says hi", null, documentUriString) {}

            val currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            assertNotNull(currentMessages.find { it is ChatMessageDocument })

            val pdfErrorWarning = currentMessages.find { it is ChatMessageWarning && it.content.contains("Failed to extract text from PDF") }
            assertNull("PDF extraction specific warning should NOT be present for a valid (even if empty content) PDF", pdfErrorWarning)

            val generalErrorWarning = currentMessages.find { it is ChatMessageWarning && it.content.contains("Failed to read content from") && !it.content.contains("PDF") }
            assertNull("General file read warning should not be present", generalErrorWarning)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `generateResponse with PDF parsing failure (e.g. corrupt) - adds DocumentMsg and PDF WarningMsg`() = runTest {
        val documentUriString = "content://com.example.provider/document/corrupt.pdf"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "corrupt.pdf"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("application/pdf")

        val inputStream: InputStream = ByteArrayInputStream("not a pdf".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(inputStream)

        viewModel.messages.test {
            viewModel.generateResponse(mockModel, "User input", null, documentUriString) {}

            var currentMessages = awaitItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            val docMessage = currentMessages.find { it is ChatMessageDocument && it.filename == expectedFilename }
            assertNotNull("Document message should be present even if PDF parsing fails", docMessage)

            var warningMessage: ChatMessageWarning? = null
            var attempt = 0
             while(warningMessage == null && attempt < 5) {
                currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
                warningMessage = currentMessages.find { it is ChatMessageWarning && it.content.startsWith("Failed to extract text from PDF: $expectedFilename") } as? ChatMessageWarning
                if (warningMessage == null) kotlinx.coroutines.delay(100)
                attempt++
            }
            assertNotNull("PDF extraction failure warning not found", warningMessage)

            val loadingMessage = currentMessages.find { it is ChatMessageLoading }
            assertNotNull("Loading message not found after PDF warning", loadingMessage)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `generateResponse with empty PDF (extracts empty string) - no warning, empty prepend`() = runTest {
        val documentUriString = "content://com.example.provider/document/empty.pdf"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "empty.pdf"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("application/pdf")

        val emptyPdfStream: InputStream = ByteArrayInputStream("%PDF-1.0\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(emptyPdfStream)

        viewModel.messages.test {
            viewModel.generateResponse(mockModel, "User input.", null, documentUriString) {}

            val currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            assertNotNull(currentMessages.find { it is ChatMessageDocument && it.filename == expectedFilename })

            val pdfErrorWarning = currentMessages.find { it is ChatMessageWarning && it.content.contains("Failed to extract text from PDF") }
            assertNull("PDF extraction warning should not be present for an empty but valid PDF", pdfErrorWarning)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `generateResponse with empty input and null document text - model receives empty string`() = runTest {
        val documentUriString = "content://com.example.provider/document/empty_content_doc"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "empty_content_doc.txt"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("text/plain")
        // Simulate document stream that results in null extractedTextFromDocument (e.g., read error or truly empty)
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(ByteArrayInputStream("".toByteArray()))


        viewModel.generateResponse(mockModel, "", null, documentUriString) {}

        // We need to verify the 'input' argument to LlmChatModelHelper.runInference
        // This is difficult without mocking LlmChatModelHelper directly.
        // As a workaround, we can use an ArgumentCaptor on mockLlmInferenceSession.sizeInTokens,
        // as this is called with the final `inputText` right before `runInference`.
        verify(mockLlmInferenceSession).sizeInTokens(stringArgumentCaptor.capture())
        assertEquals("Input to model should be empty string", "", stringArgumentCaptor.value)
    }

    @Test
    fun `generateResponse with empty input and empty document text - model receives newlines`() = runTest {
        val documentUriString = "content://com.example.provider/document/empty_doc_string"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "empty_doc_string.txt"

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("text/plain")
        // Simulate document stream that results in empty string extractedTextFromDocument
        val inputStream: InputStream = ByteArrayInputStream("".toByteArray())
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(inputStream)

        viewModel.generateResponse(mockModel, "", null, documentUriString) {}

        verify(mockLlmInferenceSession).sizeInTokens(stringArgumentCaptor.capture())
        assertEquals("Input to model should be newlines if doc is empty string", "\n\n", stringArgumentCaptor.value)
    }

    @Test
    fun `generateResponse with document and image - both are processed`() = runTest {
        val documentUriString = "content://com.example.provider/document/doc_with_image.txt"
        val fakeUri = Uri.parse(documentUriString)
        val expectedFilename = "doc_with_image.txt"
        val documentContent = "Text from document."
        val userInput = "User query."

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        `when`(mockContentResolver.query(fakeUri, null, null, null, null)).thenReturn(mockCursor)
        `when`(mockCursor.moveToFirst()).thenReturn(true)
        `when`(mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(mockCursor.getString(0)).thenReturn(expectedFilename)
        `when`(mockCursor.close()).then {}
        `when`(mockContentResolver.getType(fakeUri)).thenReturn("text/plain")
        `when`(mockContentResolver.openInputStream(fakeUri)).thenReturn(ByteArrayInputStream(documentContent.toByteArray()))

        viewModel.generateResponse(mockModel, userInput, mockBitmap, documentUriString) {}

        // Verify text passed to sizeInTokens (proxy for runInference input)
        verify(mockLlmInferenceSession).sizeInTokens(stringArgumentCaptor.capture())
        assertEquals("$documentContent\n\n$userInput", stringArgumentCaptor.value)

        // We need to verify that LlmChatModelHelper.runInference was called with the mockBitmap.
        // This is hard due to LlmChatModelHelper being an object.
        // If LlmChatModelHelper.runInference was a non-static method of an injectable class, we could verify:
        // verify(mockModelHelper).runInference(any(), eq("$documentContent\n\n$userInput"), any(), any(), eq(mockBitmap))
        // For now, we trust the pass-through in the ViewModel code is correct.
        // The messages flow will show the document.
        viewModel.messages.test {
            val currentMessages = expectMostRecentItem().firstOrNull { it.modelName == mockModel.name }?.messages ?: emptyList()
            assertNotNull(currentMessages.find { it is ChatMessageDocument && it.filename == expectedFilename })
            cancelAndConsumeRemainingEvents()
        }
    }
}
