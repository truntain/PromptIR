package com.example.myapplication

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.myapplication.ui.ImageEditorScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Luôn bật thanh trạng thái và điều hướng nền tối đồng bộ với Dark Studio
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            MyApplicationTheme {
                val state by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                val galleryLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri != null) {
                        viewModel.loadFromUri(uri)
                    }
                }

                LaunchedEffect(viewModel) {
                    viewModel.messages.collect { message ->
                        snackbarHostState.showSnackbar(message)
                    }
                }

                ImageEditorScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onNavigateHome = viewModel::navigateToHome,
                    onOpenSavedProject = viewModel::openSavedProject,
                    onDeleteProject = viewModel::deleteProject,
                    onSelectFilterMode = viewModel::selectFilterMode,
                    onSelectCategory = viewModel::selectCategory,
                    onSelectSample = { catIdx, fileName ->
                        viewModel.selectSample(fileName, catIdx)
                    },
                    onPickFromGallery = { galleryLauncher.launch("image/*") },
                    onRestore = viewModel::restore,
                    onCancel = viewModel::cancelActiveTask,
                    onSave = viewModel::saveRestored,
                    onSelectExecutionMode = viewModel::setExecutionMode,
                    onSelectFastMode = viewModel::setFastMode,
                    onRunBenchmark = viewModel::runBenchmark,
                    onSelectYoloTask = viewModel::setYoloTask,
                    onRunYolo = viewModel::runYolo,
                    onRunBatch = viewModel::runBatchEvaluation
                )
            }
        }
    }
}
