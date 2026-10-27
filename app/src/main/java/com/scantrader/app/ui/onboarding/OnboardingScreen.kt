package com.scantrader.app.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.vision.NeuralPackState
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onDone: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { Onboarding.PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val magic by viewModel.magic.collectAsStateWithLifecycle()
    val downloadStarted by viewModel.downloadStarted.collectAsStateWithLifecycle()
    val pages = Onboarding.pages(catalogs.promptCatalogs)
    val page = pagerState.currentPage
    val last = page >= Onboarding.LAST_PAGE
    val magicInstalled = magic is NeuralPackState.Ready || magic is NeuralPackState.InstalledNotLoaded
    val offerDownload = Onboarding.offerDownload(magicInstalled, catalogs.anyInstalled)
    val context = LocalContext.current
    val activity = context as? Activity
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraRequests by rememberSaveable { mutableStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraRequests += 1
        cameraGranted = granted
    }
    val canAskAgain = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
    } ?: true
    val camera = Onboarding.cameraPrompt(cameraGranted, cameraRequests, canAskAgain)

    BackHandler { viewModel.finish(onDone) }

    LaunchedEffect(pagerState.currentPage, cameraGranted, cameraRequests) {
        if (pagerState.currentPage == Onboarding.LAST_PAGE && !cameraGranted && cameraRequests == 0) {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.finish(onDone) }) {
                    Text(Onboarding.SKIP)
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { index ->
                PageBody(
                    page = pages[index],
                    icon = pageIcon(index),
                    camera = if (pages[index].requestsCamera) camera else null,
                    onAllowCamera = { permission.launch(Manifest.permission.CAMERA) },
                    onRetryCamera = { permission.launch(Manifest.permission.CAMERA) },
                    magic = if (pages[index].requestsCamera) magic else null,
                    packsReady = pages[index].requestsCamera && !offerDownload,
                )
            }
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageDots(page = page, count = Onboarding.PAGE_COUNT)
                if (last && offerDownload && !downloadStarted) {
                    OutlinedButton(
                        onClick = viewModel::downloadPacks,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Onboarding.DOWNLOAD) }
                }
                if (last && downloadStarted) {
                    Text(
                        Onboarding.DOWNLOAD_STARTED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        if (last) {
                            viewModel.finish(onDone)
                        } else {
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(Onboarding.primaryAction(page)) }
            }
        }
    }
}

@Composable
private fun PageBody(
    page: OnboardingPage,
    icon: ImageVector,
    camera: CameraPrompt?,
    onAllowCamera: () -> Unit,
    onRetryCamera: () -> Unit,
    magic: NeuralPackState?,
    packsReady: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (packsReady) {
            Spacer(Modifier.height(16.dp))
            Text(
                Onboarding.PACKS_READY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        if (camera != null) {
            Spacer(Modifier.height(16.dp))
            Onboarding.cameraMessage(camera)?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (camera == CameraPrompt.Granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }
            when (camera) {
                CameraPrompt.Ask -> {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onAllowCamera) { Text(Onboarding.ALLOW_CAMERA) }
                }
                CameraPrompt.Denied -> {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetryCamera) { Text(Onboarding.RETRY_CAMERA) }
                }
                CameraPrompt.Granted,
                CameraPrompt.DeniedForever,
                -> Unit
            }
        }
        if (magic is NeuralPackState.Downloading) {
            Spacer(Modifier.height(16.dp))
            Text(
                "${magic.label} (${(magic.progress * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { magic.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PageDots(page: Int, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Pagina ${page + 1} di $count" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == page
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (selected) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

private fun pageIcon(index: Int): ImageVector = when (index) {
    0 -> Icons.Filled.AutoAwesome
    1 -> Icons.Filled.CropFree
    2 -> Icons.Filled.Bolt
    3 -> Icons.Filled.Upload
    else -> Icons.Filled.PhotoCamera
}
