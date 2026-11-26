package com.spektralab.android.feature.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoAlbum
import com.spektralab.android.core.model.PhotoLabel
import com.spektralab.android.core.model.PhotoOrganization
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.data.local.EditStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drafts stay local until Save. All catalog work uses IO; edits and engine are never touched. */
internal class PhotoOrganizationController(private val context: Context, private val scope: CoroutineScope, private val onChanged: () -> Unit) {
    var albums by mutableStateOf<List<PhotoAlbum>>(emptyList())
        internal set
    var target by mutableStateOf<SavedPhoto?>(null)
        private set
    var draft by mutableStateOf(PhotoOrganization())
        private set
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var albumManager by mutableStateOf(false)
        private set
    private var expected = PhotoOrganization()
    val hasChanges: Boolean get() = draft != expected

    fun open(photo: SavedPhoto) = action {
        val (marks, collections) = withContext(Dispatchers.IO) {
            EditStore.photoOrganization(context, photo.assetId) to EditStore.photoAlbums(context)
        }
        expected = marks; draft = marks; albums = collections; target = photo
    }

    /**
     * 列表里原地改星级/色标/相册：不再弹「整理照片」面板，点一下就落盘。
     * 每次都先读当前记录再整体写回，避免拿旧快照覆盖别处的修改。
     */
    private fun inline(photo: SavedPhoto, change: (PhotoOrganization) -> PhotoOrganization) = action {
        val before = withContext(Dispatchers.IO) { EditStore.photoOrganization(context, photo.assetId) }
        val update = change(before)
        if (update == before) return@action
        withContext(Dispatchers.IO) { EditStore.savePhotoOrganization(context, photo.assetId, before, update) }
        onChanged()
    }

    /** 列表内联控件用的相册归属缓存（按 assetId），打开相册菜单时按需读取。 */
    var inlineMembership by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    /** 打开相册菜单时才按需读取这张片的相册归属；不走 pending，避免点开菜单后短暂点不动。 */
    fun loadInlineMembership(photo: SavedPhoto) {
        scope.launch {
            val marks = runCatching { withContext(Dispatchers.IO) { EditStore.photoOrganization(context, photo.assetId) } }
                .getOrNull() ?: return@launch
            inlineMembership = inlineMembership + (photo.assetId to marks.albums)
        }
    }

    /**
     * 列表分页加载时一次性回填整页的相册归属（含"没有任何相册"的空集）。
     * 少了这一步，已经分过组的照片在未点开相册菜单前只会显示"选择相册"。
     */
    fun primeInlineMembership(memberships: Map<String, Set<String>>) {
        if (memberships.isNotEmpty()) inlineMembership = inlineMembership + memberships
    }

    fun setRatingInline(photo: SavedPhoto, value: Int) =
        inline(photo) { it.copy(rating = value.coerceIn(0, 5)) }

    fun setLabelInline(photo: SavedPhoto, label: PhotoLabel) =
        inline(photo) { it.copy(label = label) }

    fun toggleAlbumInline(photo: SavedPhoto, albumId: String) = inline(photo) { before ->
        val after = before.copy(albums = if (albumId in before.albums) before.albums - albumId else before.albums + albumId)
        inlineMembership = inlineMembership + (photo.assetId to after.albums)
        after
    }

    fun rating(value: Int) { if (!pending) draft = draft.copy(rating = value) }
    fun label(value: PhotoLabel) { if (!pending) draft = draft.copy(label = value) }
    fun album(id: String, selected: Boolean) {
        if (!pending) draft = draft.copy(albums = if (selected) draft.albums + id else draft.albums - id)
    }
    fun close() { if (!pending) { target = null; albumManager = false; error = null } }
    fun dismissError() { error = null }

    fun save() {
        val photo = target ?: return
        val update = draft
        val before = expected
        action {
            withContext(Dispatchers.IO) { EditStore.savePhotoOrganization(context, photo.assetId, before, update) }
            target = null
            onChanged()
        }
    }

    fun manageAlbums() = action {
        albums = withContext(Dispatchers.IO) { EditStore.photoAlbums(context) }
        albumManager = true
    }
    fun createAlbum(name: String, onCreated: () -> Unit) = changeAlbum(onCreated) { EditStore.createPhotoAlbum(context, name) }
    fun renameAlbum(id: String, name: String, onSaved: () -> Unit) = changeAlbum(onSaved) { EditStore.renamePhotoAlbum(context, id, name) }
    fun deleteAlbum(id: String, onDeleted: () -> Unit) = changeAlbum(onDeleted) { EditStore.deletePhotoAlbum(context, id) }

    private fun changeAlbum(onSuccess: () -> Unit, mutation: () -> Unit) = action {
        albums = withContext(Dispatchers.IO) { mutation(); EditStore.photoAlbums(context) }
        onSuccess()
        onChanged()
    }

    private fun action(block: suspend () -> Unit) {
        if (pending) return
        pending = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = generateSequence<Throwable>(error) { it.cause }.last().message
                this@PhotoOrganizationController.error = context.getString(when (reason) {
                    "INVALID_ALBUM_NAME" -> R.string.library_album_invalid
                    "ALBUM_NAME_EXISTS" -> R.string.library_album_duplicate
                    "ALBUM_LIMIT_REACHED" -> R.string.library_album_limit
                    "ALBUM_MISSING", "ORGANIZATION_CHANGED", "EDIT_MISSING" -> R.string.library_organization_stale
                    else -> R.string.library_organization_error
                })
            } finally { pending = false }
        }
    }
}
