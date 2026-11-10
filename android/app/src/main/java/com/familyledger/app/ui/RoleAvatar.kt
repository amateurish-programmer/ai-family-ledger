package com.familyledger.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.familyledger.app.data.IdentityProfile
import com.familyledger.app.data.LocalAvatarImages
import com.familyledger.app.data.FamilyAvatarCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Thumbnail IDs are immutable. The header, chat messages and report can reuse one decoded image.
private val thumbnails = LruCache<String, ImageBitmap>(3)

@Composable
internal fun RoleAvatar(avatar: String, modifier: Modifier = Modifier) {
    val fallback = IdentityProfile.avatars.firstOrNull { it.id == avatar } ?: IdentityProfile.avatars.first()
    if (!IdentityProfile.isPhotoAvatar(avatar) && !FamilyAvatarCache.isPhoto(avatar)) {
        ProfileBadge(fallback, modifier)
        return
    }
    val context = LocalContext.current.applicationContext
    key(avatar) {
        val image by produceState<ImageBitmap?>(null, avatar) {
            value = withContext(Dispatchers.IO) {
                val file = LocalAvatarImages.file(context, avatar) ?: FamilyAvatarCache.file(context, avatar) ?: return@withContext null
                synchronized(thumbnails) {
                    thumbnails.get(avatar) ?: BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.also {
                        thumbnails.put(avatar, it)
                    }
                }
            }
        }
        val bitmap = image
        if (bitmap == null) ProfileBadge(fallback, modifier)
        else Image(bitmap, "自定义角色头像", modifier.size(LocalLedgerStyle.current.iconSize).clip(CircleShape),
            contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun MemberAvatar(member: String, localRole: String, localAvatar: String, modifier: Modifier = Modifier,
    memberAvatars: Map<String, String> = emptyMap()) {
    if (memberAvatars[member] == FamilyAvatarCache.AMBIGUOUS) IconBadge(Icons.Outlined.PersonOutline, modifier, sage = true)
    else if (member.isNotBlank() && member == localRole) RoleAvatar(localAvatar, modifier)
    else if (member in memberAvatars) RoleAvatar(memberAvatars.getValue(member), modifier)
    else IconBadge(Icons.Outlined.PersonOutline, modifier, sage = true)
}
