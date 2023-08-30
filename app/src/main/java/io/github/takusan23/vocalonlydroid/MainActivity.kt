package io.github.takusan23.vocalonlydroid

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.takusan23.vocalonlydroid.ui.theme.VocalOnlyDroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VocalOnlyDroidTheme {
                HomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 音声ファイルの Uri
    val normalTrackUri = remember { mutableStateOf<Uri?>(null) }
    val instrumentalTrackUri = remember { mutableStateOf<Uri?>(null) }

    // ファイルピッカー
    val normalTrackFilePicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { normalTrackUri.value = it }
    val instrumentalTrackFilePicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { instrumentalTrackUri.value = it }

    val progressStatus = remember { mutableStateOf(ProgressStatus.IDLE) }

    // 処理内容
    fun start() {
        scope.launch(Dispatchers.Default) {

            // 作業用の仮フォルダを作る
            val tempFolder = context.getExternalFilesDir(null)?.resolve("temp_work")!!.apply {
                mkdir()
            }

            // 音声ファイルをデコードする
            progressStatus.value = ProgressStatus.DECODE
            val (normalRawFile, instrumentalRawFile) = listOf(normalTrackUri.value!!, instrumentalTrackUri.value!!)
                .mapIndexed { index, uri ->
                    // 並列でデコードしてファイルを返す
                    async {
                        context.contentResolver.openFileDescriptor(uri, "r")!!.use {
                            val rawFile = File(tempFolder, "audio_$index.raw").apply { createNewFile() }
                            VocalOnlyProcessor.decode(
                                fileDescriptor = it.fileDescriptor,
                                outputFile = rawFile
                            )
                            return@use rawFile
                        }
                    }
                }
                // 並列で実行した処理を待ち合わせる
                .map { it.await() }

            // ボーカルだけ取り出す
            progressStatus.value = ProgressStatus.EDIT
            val vocalRawFile = tempFolder.resolve("audio_vocal.raw").apply { createNewFile() }
            VocalOnlyProcessor.extract(
                normalTrackFile = normalRawFile,
                instrumentalTrackFile = instrumentalRawFile,
                resultFile = vocalRawFile
            )

            // 生データをエンコードする
            progressStatus.value = ProgressStatus.ENCODE
            val encodeVocalFile = tempFolder.resolve("audio_vocal.aac").apply { createNewFile() }
            VocalOnlyProcessor.encode(
                rawFile = vocalRawFile,
                resultFile = encodeVocalFile
            )

            // ファイルを音楽フォルダにコピーする
            progressStatus.value = ProgressStatus.ONE_MORE_THING
            val fileName = VocalOnlyProcessor.getFileNameFromUri(context, normalTrackUri.value!!)!!
            VocalOnlyProcessor.copyToAudioFolder(
                context = context,
                fileName = "$fileName.aac",
                targetFile = encodeVocalFile
            )

            // 後始末
            tempFolder.deleteRecursively()
            progressStatus.value = ProgressStatus.IDLE
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) }) }
    ) {
        Column(
            modifier = Modifier.padding(it),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            Button(
                onClick = { normalTrackFilePicker.launch(arrayOf("audio/*")) }
            ) { Text(text = normalTrackUri.value?.toString() ?: "通常版の選択") }

            Button(
                onClick = { instrumentalTrackFilePicker.launch(arrayOf("audio/*")) }
            ) { Text(text = instrumentalTrackUri.value?.toString() ?: "カラオケ版の選択") }

            // 実行中は実行ボタンを出さない
            if (progressStatus.value == ProgressStatus.IDLE) {
                Button(
                    onClick = { start() }
                ) { Text(text = "処理を始める") }
            } else {
                CircularProgressIndicator()
                Text(text = "処理中です：${progressStatus.value}")
            }
        }
    }
}

private enum class ProgressStatus {
    /** 実行可能 */
    IDLE,

    /** デコード中 */
    DECODE,

    /** 音声の加工中 */
    EDIT,

    /** エンコード中 */
    ENCODE,

    /** あとしまつ */
    ONE_MORE_THING
}