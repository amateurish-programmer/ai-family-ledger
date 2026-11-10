package com.familyledger.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

internal fun withIsolatedAvatarProfile(block: (Context) -> Unit) {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val id = UUID.randomUUID().toString()
    val directory = File(base.cacheDir, "avatar-test-$id").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = directory
        override fun getCacheDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$id", mode)
    }
    try { block(context) } finally {
        directory.deleteRecursively()
        listOf("cloud_public", "cloud_encrypted").forEach { base.deleteSharedPreferences("$it-$id") }
    }
}

class LocalAvatarImagesTest {
    @Test fun photoIsCroppedOrientedAndIndependentOfItsOriginalAfterSave() = withIsolatedAvatarProfile { context ->
        val source = File(context.cacheDir, "picked.jpg")
        val sourceBitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sourceBitmap)
        canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f, 0f, 1024f, 1024f, android.graphics.Paint().apply { color = Color.RED })
        source.outputStream().use { sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        sourceBitmap.recycle()
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val cloud = CloudService(context)
        val preview = source.inputStream().use { LocalAvatarImages.decode(context, it) }
        assertTrue(source.delete())
        assertEquals(256, preview.width)
        assertEquals(256, preview.height)
        assertEquals("person", cloud.localAvatar())
        cloud.setLocalIdentity("家人", "person", "local", preview)
        val reopened = CloudService(context)
        val saved = reopened.localAvatar()
        val image = LocalAvatarImages.file(context, saved)!!
        assertTrue(image.length() in 1L..65536L)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()), image.readBytes().take(2).toByteArray())
        val thumbnail = BitmapFactory.decodeFile(image.path)
        assertTrue(Color.red(thumbnail.getPixel(128, 32)) > 200)
        assertTrue(Color.blue(thumbnail.getPixel(128, 224)) > 200)
        assertEquals("家人", reopened.localRole())
        reopened.setLocalIdentity("家人", "cat")
        assertNull(LocalAvatarImages.file(context, saved))
    }

    @Test fun failedImportOrStaleOwnerKeepsSavedPhotoAndNeverClaimsAnotherOwnersImage() = withIsolatedAvatarProfile { context ->
        val cloud = CloudService(context)
        val preview = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        cloud.setLocalIdentity("家人", "person", "local", preview)
        val old = cloud.localAvatar()
        val opaque = BitmapFactory.decodeFile(LocalAvatarImages.file(context, old)!!.path)
        assertEquals(Color.WHITE, opaque.getPixel(100, 100))
        assertThrows(IllegalArgumentException::class.java) {
            "broken".byteInputStream().use { LocalAvatarImages.decode(context, it) }
        }
        assertThrows(IllegalStateException::class.java) { cloud.setLocalIdentity("其他", "cat", "other", preview) }
        assertEquals(old, cloud.localAvatar())
        assertEquals("家人", cloud.localRole())
        val other = LocalAvatarImages.write(context, preview)
        context.getSharedPreferences("cloud_public", Context.MODE_PRIVATE).edit().putString("localAvatar:other", other).commit()
        assertThrows(IllegalArgumentException::class.java) { cloud.setLocalIdentity("家人", other) }
        cloud.setLocalIdentity("家人", old, "local", preview)
        assertNotEquals(old, cloud.localAvatar())
        assertNull(LocalAvatarImages.file(context, old))
        assertNotNull(LocalAvatarImages.file(context, other))
        assertNull(LocalAvatarImages.file(context, "../$other"))
    }

    @Test fun oversizedPickerStreamIsStoppedAndLeavesNoOriginalInCache() = withIsolatedAvatarProfile { context ->
        var bytesRead = 0L
        val stream = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                bytesRead += length
                check(bytesRead <= 33L * 1024 * 1024) { "Import did not bound the stream" }
                return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalAvatarImages.decode(context, stream) }
        assertTrue(bytesRead > 32L * 1024 * 1024)
        assertTrue(context.cacheDir.listFiles()!!.isEmpty())
    }
}
