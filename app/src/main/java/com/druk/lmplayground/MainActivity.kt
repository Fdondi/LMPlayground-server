package com.druk.lmplayground

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var folderPickerCallback: ((Uri?) -> Unit)? = null

    val folderPickerLauncher: ActivityResultLauncher<Uri?> = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        folderPickerCallback?.invoke(uri)
        folderPickerCallback = null
    }

    fun launchFolderPicker(callback: (Uri?) -> Unit) {
        folderPickerCallback = callback
        try {
            folderPickerLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            // Some devices ship without (or with disabled) DocumentsUI —
            // OPEN_DOCUMENT_TREE has no handler and ActivityResultLauncher
            // throws synchronously. Surface a message and clear the callback
            // so the caller's UI doesn't stay wedged waiting for a result.
            folderPickerCallback = null
            Toast.makeText(
                this,
                R.string.folder_picker_unavailable,
                Toast.LENGTH_LONG,
            ).show()
            callback(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.content_main)
    }
}
