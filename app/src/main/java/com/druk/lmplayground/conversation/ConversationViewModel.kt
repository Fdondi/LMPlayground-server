package com.druk.lmplayground.conversation

import android.app.Application
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.App
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

class ConversationViewModel(val app: Application) : AndroidViewModel(app) {

    private val llamaCpp: LlamaCpp? = (app as? App)?.llamaCpp
    private var llamaModel: LlamaModel? = null
    private var llamaSession: LlamaGenerationSession? = null
    private var generatingJob: Job? = null
    
    // Keep strong reference to prevent GC from closing the file descriptor
    private var modelFileHandle: StorageRepository.ModelFileHandle? = null
    
    private val _isGenerating = MutableLiveData(false)
    private val _isModelReady = MutableLiveData(false)
    private val _modelLoadingProgress = MutableLiveData(0f)
    private val _loadedModel = MutableLiveData<ModelInfo?>(null)
    private val _loadedModelStatus = MutableLiveData<String?>(null)
    private val _models = MutableLiveData<List<ModelWithStatus>>(emptyList())
    
    private val storagePreferences = StoragePreferences(app)
    val storageRepository = StorageRepository(app, storagePreferences)

    val isGenerating: LiveData<Boolean> = _isGenerating
    val isModelReady: LiveData<Boolean> = _isModelReady
    val modelLoadingProgress: LiveData<Float> = _modelLoadingProgress
    val loadedModel: LiveData<ModelInfo?> = _loadedModel
    val loadedModelStatus: LiveData<String?> = _loadedModelStatus
    val models: LiveData<List<ModelWithStatus>> = _models

    val uiState = ConversationUiState(
        initialMessages = emptyList()
    )

    override fun onCleared() {
        val job = generatingJob
        val session = llamaSession
        val model = llamaModel
        val handle = modelFileHandle
        generatingJob = null
        llamaSession = null
        llamaModel = null
        modelFileHandle = null

        CoroutineScope(Dispatchers.Default).launch {
            job?.cancel()
            job?.join()
            session?.destroy()
            model?.unloadModel()
            handle?.close()
        }
        super.onCleared()
    }

    @MainThread
    fun loadModelList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val downloadedFilenames = storageRepository.getModelFiles().map { it.name }.toSet()
                _models.postValue(
                    ModelInfoProvider.getModelsWithStatus(downloadedFilenames)
                )
            }
        }
    }

    @MainThread
    fun loadModel(modelInfo: ModelInfo) {
        val llamaCpp = llamaCpp ?: return
        _models.postValue(emptyList())
        _isModelReady.postValue(false)
        
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                _modelLoadingProgress.postValue(0f)
                _loadedModel.postValue(modelInfo)
                _loadedModelStatus.postValue("Loading...")
                
                // Close any previous file handle
                modelFileHandle?.close()
                modelFileHandle = null
                
                // Open model file via SAF - returns "fd:N" path format
                // Our native ggml_fopen/llama_open overrides use dup() to create copies
                val fileHandle = storageRepository.openModelFile(modelInfo.filename)
                if (fileHandle == null) {
                    _loadedModelStatus.postValue("Cannot open file")
                    return@withContext
                }
                
                // IMPORTANT: Keep handle alive while model is loaded (prevents GC from closing fd)
                modelFileHandle = fileHandle
                
                val llamaModel = llamaCpp.loadModel(
                    fileHandle.path,
                    modelInfo.inputPrefix,
                    modelInfo.inputSuffix,
                    modelInfo.antiPrompt,
                    object: LlamaProgressCallback {
                        override fun onProgress(progress: Float) {
                            val progressDescription = "${round(100 * progress).toInt()}%"
                            _modelLoadingProgress.postValue(progress)
                            _loadedModelStatus.postValue(progressDescription)
                        }
                    }
                )
                val modelSize = llamaModel.getModelSize()
                val modelDescription = Formatter.formatFileSize(app, modelSize)
                val llamaSession = llamaModel.createSession()
                this@ConversationViewModel.llamaModel = llamaModel
                this@ConversationViewModel.llamaSession = llamaSession
                _modelLoadingProgress.postValue(0f)
                _loadedModelStatus.postValue(modelDescription)
                _isModelReady.postValue(true)
            }
        }
    }

    @MainThread
    fun addMessage(message: Message) {
        uiState.addMessage(message)
        uiState.addMessage(
            Message(
                "Assistant",
                ""
            )
        )

        val antiPrompt = _loadedModel.value?.antiPrompt
        _isGenerating.postValue(true)
        generatingJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext
                llamaSession.addMessage(message.content)

                val callback = object: LlamaGenerationCallback {
                    var responseByteArray = ByteArray(0)
                    override fun newTokens(newTokens: ByteArray) {
                        responseByteArray += newTokens
                        var string = String(responseByteArray, Charsets.UTF_8)
                        for (suffix in antiPrompt ?: emptyArray()) {
                            string = string.removeSuffix(suffix)
                            string = string.removeSuffix(suffix + "\n")
                        }
                        uiState.updateLastMessage(string)
                    }
                }
                while (this.isActive && llamaSession.generate(callback) == 0) {
                    // wait for the response
                }
                llamaSession.printReport()
                _isGenerating.postValue(false)
            }
        }
    }

    @MainThread
    fun cancelGeneration() {
        generatingJob?.cancel()
        generatingJob = null
    }

    fun getReport(): String? {
        return llamaSession?.getReport()
    }

    fun unloadModel() {
        viewModelScope.launch {
            if (modelFileHandle != null || llamaModel != null) {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                withContext(Dispatchers.Default) {
                    llamaSession?.destroy()
                    llamaSession = null
                    llamaModel?.unloadModel()
                    llamaModel = null
                }

                modelFileHandle?.close()
                modelFileHandle = null
                
                _loadedModel.postValue(null)
                _loadedModelStatus.postValue(null)
                _isModelReady.postValue(false)
            }
            uiState.resetMessages()
        }
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }
}
