package com.familyledger.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoAvatarIdTest {
    @Test fun acceptsPresetOrGeneratedPhotoIdAndRejectsPathsAndUris() {
        val photo = "photo-00000000-0000-4000-8000-000000000001"
        assertEquals(photo, IdentityProfile.avatar(photo))
        assertEquals("cat", IdentityProfile.avatar("cat"))
        listOf("../$photo", "$photo.png", "/cache/$photo", "content://images/$photo", "photo-").forEach {
            assertThrows(IllegalArgumentException::class.java) { IdentityProfile.avatar(it) }
        }
    }
}
