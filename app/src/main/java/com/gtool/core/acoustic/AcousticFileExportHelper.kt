package com.gtool.core.acoustic

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * 离线文件保存与系统分享工具：
 * - PNG 频谱图/对比图一键存入系统相册（Pictures/TickTuner，Android 10+ 零存储权限）
 * - MD / PDF / CSV / JSON 支持 SAF 另存为任意目录 与 FileProvider 一键系统分享
 */
object AcousticFileExportHelper {

    data class SavedImageResult(
        val uri: Uri,
        val displayLocation: String
    )

    fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        fileNameWithoutExt: String
    ): Result<SavedImageResult> = runCatching {
        val fileName = "$fileNameWithoutExt.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TickTuner")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("系统相册创建图片记录失败")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 压缩写入失败" }
                } ?: error("无法打开相册输出流")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SavedImageResult(uri, "相册 Pictures/TickTuner/$fileName")
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "TickTuner")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            outFile.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 压缩写入失败" }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            SavedImageResult(uri, outFile.absolutePath)
        }
    }

    fun writeTempExportUri(
        context: Context,
        fileName: String,
        writer: (OutputStream) -> Unit
    ): Result<Uri> = runCatching {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            writer(out)
            out.flush()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun writeToContentUri(
        context: Context,
        uri: Uri,
        writer: (OutputStream) -> Unit
    ): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            writer(out)
            out.flush()
        } ?: error("无法写入目标文件")
    }

    fun shareUri(
        context: Context,
        uri: Uri,
        mimeType: String,
        chooserTitle: String
    ) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
