package com.familyledger.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

object LocalAvatarImages {
    private const val SIZE = 256
    private const val INPUT_LIMIT = 32 * 1024 * 1024
    private fun directory(context: Context) = File(context.filesDir, "role-avatars")

    fun file(context: Context, avatar: String): File? {
        if (!IdentityProfile.isPhotoAvatar(avatar)) return null
        return File(directory(context), "$avatar.jpg").takeIf { it.isFile }
    }

    /** Only the small preview survives this call. An unlinked file lets us rewind even a pipe-backed picker result. */
    internal fun decode(context: Context, input: InputStream): Bitmap {
        val scratch = File.createTempFile("avatar-input-", null, context.cacheDir)
        try {
            return RandomAccessFile(scratch, "rw").use { source ->
                check(scratch.delete()) { "无法准备头像预览" }
                var total = 0
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    require(total <= INPUT_LIMIT) { "头像图片须在 32 MB 以内" }
                    source.write(buffer, 0, count)
                }
                source.seek(0)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(source.fd, null, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "无法读取这张图片，请重新选择" }
                val longest = maxOf(options.outWidth, options.outHeight)
                require(longest <= 65535 && options.outWidth.toLong() * options.outHeight <= 256_000_000L) {
                    "图片尺寸过大，请先裁剪后再选择"
                }
                var sample = 1
                while (longest / sample > 1024) sample *= 2
                options.inSampleSize = sample
                options.inJustDecodeBounds = false
                source.seek(0)
                val orientation = try {
                    ExifInterface(source.fd).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: java.io.IOException) { ExifInterface.ORIENTATION_NORMAL }
                source.seek(0)
                val decoded = requireNotNull(BitmapFactory.decodeFileDescriptor(source.fd, null, options)) {
                    "无法读取这张图片，请重新选择"
                }
                val edge = minOf(decoded.width, decoded.height)
                val left = (decoded.width - edge) / 2
                val top = (decoded.height - edge) / 2
                val thumbnail = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                Canvas(thumbnail).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, Rect(left, top, left + edge, top + edge), Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                decoded.recycle()
                orient(thumbnail, orientation)
            }
        } finally { if (scratch.exists()) scratch.delete() }
    }

    private fun orient(image: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return image
        }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true).also {
            if (it !== image) image.recycle()
        }
    }

    internal fun write(context: Context, preview: Bitmap): String {
        require(preview.width == SIZE && preview.height == SIZE) { "请重新选择头像图片" }
        val dir = directory(context)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建头像目录" }
        val id = "photo-${UUID.randomUUID()}"
        val target = File(dir, "$id.jpg")
        val opaque = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(preview, 0f, 0f, null) }
        val jpeg = ByteArrayOutputStream()
        try {
            for (quality in listOf(88, 75, 60)) {
                jpeg.reset()
                check(opaque.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) { "头像图片保存失败" }
                if (jpeg.size() <= 64 * 1024) break
            }
            check(jpeg.size() in 1..64 * 1024) { "头像图片压缩失败，请重新选择" }
            target.outputStream().use { output ->
                jpeg.writeTo(output)
                output.fd.sync()
            }
        } catch (e: Exception) { target.delete(); throw e }
        finally { opaque.recycle() }
        return id
    }

    /** Called after the profile preference commit, under the same lock as all profile writes. */
    internal fun removeUnreferenced(context: Context, retained: Set<String>) {
        directory(context).listFiles()?.forEach { file ->
            if (file.extension == "jpg" && IdentityProfile.isPhotoAvatar(file.nameWithoutExtension) &&
                file.nameWithoutExtension !in retained) file.delete()
        }
    }
}
