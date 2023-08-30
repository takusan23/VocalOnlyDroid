package io.github.takusan23.vocalonlydroid

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import kotlin.math.max

/** ボーカルだけ取り出す処理を行う */
object VocalOnlyProcessor {

    /**
     * 音声ファイルをデコードする
     *
     * @param fileDescriptor [android.content.ContentResolver.openFileDescriptor]
     * @param outputFile 出力先ファイル
     */
    suspend fun decode(
        fileDescriptor: FileDescriptor,
        outputFile: File
    ) = withContext(Dispatchers.Default) {
        // コンテナフォーマットからデータを取り出すやつ
        val extractor = MediaExtractor().apply {
            setDataSource(fileDescriptor)
        }
        // 音声トラックを見つける
        // 音声ファイルなら、音声トラックしか無いはずなので、0 決め打ちでも良さそう
        val audioTrackIndex = (0 until extractor.trackCount)
            .first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        // デコーダーにメタデータを渡す
        val audioDecoder = AudioDecoder().apply {
            prepareDecoder(extractor.getTrackFormat(audioTrackIndex))
        }
        extractor.selectTrack(audioTrackIndex)
        // ファイルに書き込む準備
        outputFile.outputStream().use { outputStream ->
            // デコードする
            audioDecoder.startAudioDecode(
                readSampleData = { byteBuffer ->
                    // データを進める
                    val size = extractor.readSampleData(byteBuffer, 0)
                    extractor.advance()
                    size to extractor.sampleTime
                },
                onOutputBufferAvailable = { bytes ->
                    // データを書き込む
                    outputStream.write(bytes)
                }
            )
        }
    }

    /**
     * 通常版からカラオケ版を引いてボーカルだけ取り出す
     *
     * @param normalTrackFile 通常版のデコード済みデータ
     * @param instrumentalTrackFile カラオケ版のデコード済みデータ
     * @param resultFile 保存先
     */
    suspend fun extract(
        normalTrackFile: File,
        instrumentalTrackFile: File,
        resultFile: File
    ) = withContext(Dispatchers.IO) {
        resultFile.outputStream().use { resultOutputStream ->
            normalTrackFile.inputStream().use { normalTrackInputStream ->
                instrumentalTrackFile.inputStream().use { instrumentalTrackInputStream ->
                    // データが無くなるまで
                    while (isActive) {
                        // ちょっとずつ取り出して、音の加工をしていく
                        // 一気に読み取るのは多分無理
                        val normalTrackByteArray = ByteArray(BYTE_ARRAY_SIZE).also { byteArray -> normalTrackInputStream.read(byteArray) }
                        val instrumentalTrackByteArray = ByteArray(BYTE_ARRAY_SIZE).also { byteArray -> instrumentalTrackInputStream.read(byteArray) }

                        // 通常版からカラオケ版を引く処理
                        val size = max(normalTrackByteArray.size, instrumentalTrackByteArray.size)
                        val vocalOnlyByteArray = (0 until size)
                            .map { index -> (normalTrackByteArray[index] - instrumentalTrackByteArray[index]).toByte() }
                            .toByteArray()
                        // ファイルに書き込む
                        resultOutputStream.write(vocalOnlyByteArray)

                        // どちらかのファイルが読み込み終わったら、無限ループを抜ける
                        if (normalTrackInputStream.available() == 0 || instrumentalTrackInputStream.available() == 0) {
                            break
                        }
                    }
                }
            }
        }
    }

    /**
     * エンコードする
     *
     * @param rawFile 圧縮していないデータ
     * @param resultFile エンコードしたデータ
     */
    suspend fun encode(
        rawFile: File,
        resultFile: File
    ) = withContext(Dispatchers.Default) {
        // エンコーダーを初期化
        val audioEncoder = AudioEncoder().apply {
            prepareEncoder(
                codec = MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate = 44_100,
                channelCount = 2,
                bitRate = 192_000
            )
        }
        // コンテナフォーマットに保存していくやつ
        val mediaMuxer = MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        rawFile.inputStream().use { inputStream ->
            audioEncoder.startAudioEncode(
                onRecordInput = { bytes ->
                    // データをエンコーダーに渡す
                    inputStream.read(bytes)
                },
                onOutputBufferAvailable = { byteBuffer, bufferInfo ->
                    // 無いと思うけど MediaMuxer が開始していなければ追加しない
                    if (trackIndex != -1) {
                        mediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                    }
                },
                onOutputFormatAvailable = {
                    // フォーマットが確定したら MediaMuxer を開始する
                    trackIndex = mediaMuxer.addTrack(it)
                    mediaMuxer.start()
                }
            )
        }
        mediaMuxer.stop()
    }

    /**
     * 音楽ファイルを端末の音声フォルダにコピーする
     *
     * @param context [Context]
     * @param fileName ファイル名
     * @param targetFile 音楽ファイル
     */
    suspend fun copyToAudioFolder(
        context: Context,
        fileName: String,
        targetFile: File
    ) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        // 名前とか
        val contentValues = contentValuesOf(
            MediaStore.Audio.Media.DISPLAY_NAME to fileName,
            // ディレクトリを掘る場合
            MediaStore.Audio.Media.RELATIVE_PATH to "${Environment.DIRECTORY_MUSIC}/VocalOnlyTrack"
        )
        // 追加する
        val uri = contentResolver.insert(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return@withContext
        // ファイルをコピーする
        targetFile.inputStream().use { inputStream ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                // Kotlin 拡張関数でコピー 一発
                inputStream.copyTo(outputStream)
            }
        }
    }

    /**
     * Uri からファイル名をクエリする
     *
     * @param uri [Uri]
     * @param context [Context]
     * @return ファイル名
     */
    suspend fun getFileNameFromUri(
        context: Context,
        uri: Uri
    ) = withContext(Dispatchers.IO) {
        // DISPLAY_NAME を SELECT する
        context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
            // DB の先頭に移動して、
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
        }
    }

    private const val BYTE_ARRAY_SIZE = 8192

}