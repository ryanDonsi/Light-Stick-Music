package com.dongsitech.lightstickmusicdemo.ui

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import java.io.File

@Composable
fun MusicPlayerScreen(viewModel: MusicPlayerViewModel = viewModel()) {
    val context = LocalContext.current
    val title by viewModel.title.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val musicFiles by viewModel.musicFiles.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()

    // Android 13 �̻��� READ_MEDIA_AUDIO, �� �ܴ� READ_EXTERNAL_STORAGE
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadMusicWithMediaStore(context)
        } else {
            Toast.makeText(context, "권한 없음", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        } else {
            viewModel.loadMusicWithMediaStore(context)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = "���� ��� ��: $title", style = MaterialTheme.typography.headlineSmall)

            Spacer(modifier = Modifier.height(16.dp))

            // ���� �ð� �� �����̴�
            if (duration > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(formatMillis(currentPosition), modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { viewModel.seekTo(it.toInt()) },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatMillis(duration), modifier = Modifier.padding(start = 8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.Center) {
                Button(onClick = { viewModel.playPauseMusic() }) {
                    Text(if (isPlaying) "�Ͻ�����" else "���")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(onClick = { viewModel.stopMusic() }) {
                    Text("����")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("���� ���� ���", style = MaterialTheme.typography.titleMedium)

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(musicFiles) { file ->
                    Text(
                        text = file.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectAndPlay(file) }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun formatMillis(millis: Int): String {
    val minutes = millis / 1000 / 60
    val seconds = (millis / 1000) % 60
    return "%02d:%02d".format(minutes, seconds)
}
