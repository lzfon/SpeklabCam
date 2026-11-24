package com.spektralab.android.data.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.spektralab.android.core.model.CanonicalJson
import com.spektralab.android.core.model.EditHistoryPosition
import com.spektralab.android.core.model.EditorParams
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Uses its owner's SQLite transaction: step, cursor and the current edit commit together.
 * IDs are library edit IDs, so source relocation never moves/copies a history.
 */
internal class EditHistoryRepository(private val db: SQLiteDatabase, private val validate: (String) -> Unit) {
    enum class Checkpoint { STEP_WRITTEN, CURSOR_MOVED, EDIT_WRITTEN }
    internal var checkpoint: (Checkpoint) -> Unit = {}
    private val hashes = object : LinkedHashMap<String, String>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8
    }
    private fun hash(json: String): String = if (json.length > 64 * 1024) fingerprint(json)
        else hashes.getOrPut(json) { fingerprint(json) }

    private data class Head(val generation: Long, val cursor: Long, val earlier: Long, val group: String?)

    private fun head(id: String): Head? = db.rawQuery(
        "SELECT generation,cursor,earlier,merge_key,format FROM edit_history_heads WHERE edit_id=?", arrayOf(id),
    ).use {
        if (!it.moveToFirst()) null else {
            check(it.getInt(4) == 1) { "FUTURE_HISTORY_READ_ONLY" }
            Head(it.getLong(0), it.getLong(1), it.getLong(2), if (it.isNull(3)) null else it.getString(3))
        }
    }

    fun ensure(id: String, assetId: String, current: String): EditHistoryPosition {
        validate(current)
        val existing = head(id)
        if (existing == null) {
            insertStep(id, 1, current, "baseline", "[]")
            db.insertOrThrow("edit_history_heads", null, ContentValues().apply {
                put("edit_id", id); put("asset_id", assetId); put("generation", 1L)
                put("cursor", 1L); put("earlier", 0L); put("format", 1)
            })
        } else check(hash(payload(id, existing.cursor)) == hash(current)) { "HISTORY_EDIT_DIVERGED" }
        return position(id, current)
    }

    fun append(id: String, assetId: String, before: String, after: String, group: String?, origin: String,
               expectedGeneration: Long? = null): EditHistoryPosition {
        ensure(id, assetId, before)
        val old = requireNotNull(head(id))
        check(expectedGeneration == null || old.generation == expectedGeneration) { "HISTORY_CHANGED" }
        validate(after)
        val afterHash = hash(after)
        if (afterHash == hash(before)) return position(id, after)
        // A new edit after undo replaces the redo branch. Cursor moves themselves never append.
        db.delete("edit_history_steps", "edit_id=? AND seq>?", arrayOf(id, old.cursor.toString()))
        val first = db.rawQuery("SELECT MIN(seq) FROM edit_history_steps WHERE edit_id=?", arrayOf(id)).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val merge = group != null && group == old.group && old.cursor > first
        val sequence = if (merge) old.cursor else old.cursor + 1
        val label = changedKeys(before, after)
        if (merge) db.update("edit_history_steps", ContentValues().apply {
            put("payload", encode(after)); put("hash", afterHash); put("at_ms", System.currentTimeMillis())
            put("origin", origin); put("label", label)
        }, "edit_id=? AND seq=?", arrayOf(id, sequence.toString()))
        else insertStep(id, sequence, after, origin, label)
        checkpoint(Checkpoint.STEP_WRITTEN)
        db.update("edit_history_heads", ContentValues().apply {
            put("generation", old.generation + 1); put("cursor", sequence)
            if (group == null) putNull("merge_key") else put("merge_key", group)
        }, "edit_id=?", arrayOf(id))
        trim(id, sequence)
        return position(id, after)
    }

    fun move(id: String, current: String, expectedGeneration: Long, redo: Boolean): EditHistoryPosition {
        val old = requireNotNull(head(id)) { "HISTORY_MISSING" }
        check(old.generation == expectedGeneration) { "HISTORY_CHANGED" }
        position(id, current) // Reject a diverged or corrupt cursor before changing anything.
        val relation = if (redo) ">" else "<"
        val order = if (redo) "ASC" else "DESC"
        val target = db.rawQuery("SELECT seq FROM edit_history_steps WHERE edit_id=? AND seq$relation? ORDER BY seq $order LIMIT 1",
            arrayOf(id, old.cursor.toString())).use { if (it.moveToFirst()) it.getLong(0) else return position(id, current) }
        val json = payload(id, target)
        validate(json)
        db.update("edit_history_heads", ContentValues().apply {
            put("generation", old.generation + 1); put("cursor", target); putNull("merge_key")
        }, "edit_id=?", arrayOf(id))
        checkpoint(Checkpoint.CURSOR_MOVED)
        return position(id, json)
    }

    fun position(id: String, current: String): EditHistoryPosition {
        val head = requireNotNull(head(id)) { "HISTORY_MISSING" }
        val cursorHash = db.rawQuery("SELECT hash FROM edit_history_steps WHERE edit_id=? AND seq=?",
            arrayOf(id, head.cursor.toString())).use { check(it.moveToFirst()) { "HISTORY_CURSOR_MISSING" }; it.getString(0) }
        check(cursorHash == hash(current)) { "HISTORY_EDIT_DIVERGED" }
        return db.rawQuery("SELECT MIN(seq),MAX(seq),COUNT(*) FROM edit_history_steps WHERE edit_id=?", arrayOf(id)).use {
            check(it.moveToFirst())
            EditHistoryPosition(id, head.generation, head.cursor, head.cursor > it.getLong(0),
                head.cursor < it.getLong(1), current, it.getInt(2), head.earlier)
        }
    }

    private fun payload(id: String, sequence: Long): String = db.rawQuery(
        "SELECT payload,hash FROM edit_history_steps WHERE edit_id=? AND seq=?", arrayOf(id, sequence.toString()),
    ).use {
        check(it.moveToFirst()) { "HISTORY_STEP_MISSING" }
        val bytes = it.getBlob(0)
        check(bytes.size <= MAX_STATE_BYTES + 4096) { "HISTORY_PAYLOAD_TOO_LARGE" }
        val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(result.size() + count <= MAX_STATE_BYTES) { "HISTORY_PAYLOAD_TOO_LARGE" }
                result.write(buffer, 0, count)
            }
            result.toString("UTF-8")
        }
        check(hash(json) == it.getString(1)) { "HISTORY_PAYLOAD_CHANGED" }
        json
    }

    private fun insertStep(id: String, sequence: Long, json: String, origin: String, label: String) {
        db.insertOrThrow("edit_history_steps", null, ContentValues().apply {
            put("edit_id", id); put("seq", sequence); put("step_id", UUID.randomUUID().toString())
            put("at_ms", System.currentTimeMillis()); put("origin", origin); put("label", label)
            put("payload", encode(json)); put("hash", hash(json))
        })
    }

    private fun trim(id: String, cursor: Long) {
        var removed = 0L
        while (true) {
            val over = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(payload)),0) FROM edit_history_steps WHERE edit_id=?",
                arrayOf(id)).use { check(it.moveToFirst()); it.getInt(0) > MAX_STATES || it.getLong(1) > MAX_HISTORY_BYTES }
            if (!over) break
            val first = db.rawQuery("SELECT MIN(seq) FROM edit_history_steps WHERE edit_id=? AND seq<?",
                arrayOf(id, cursor.toString())).use { check(it.moveToFirst() && !it.isNull(0)) { "HISTORY_BUDGET" }; it.getLong(0) }
            db.delete("edit_history_steps", "edit_id=? AND seq=?", arrayOf(id, first.toString())); removed++
        }
        if (removed > 0) db.execSQL("UPDATE edit_history_heads SET earlier=earlier+? WHERE edit_id=?", arrayOf<Any>(removed, id))
    }

    companion object {
        const val MAX_STATES = 256
        const val MAX_STATE_BYTES = 2 * 1024 * 1024
        const val MAX_HISTORY_BYTES = 8L * 1024 * 1024

        /** UI defaults normalize old partial sidecars; automatic film lettering is not an edit. */
        internal fun fingerprint(json: String): String = CanonicalJson.fingerprint(CanonicalJson.encode(
            EditorParams.fromJson(JSONObject(json)).toJson().apply {
                remove("overscan_edge_text")
                // R04's new neutral defaults must not invalidate existing R03 digests.
                for (key in listOf("camera_wb_temperature_as_shot", "camera_wb_tint_as_shot"))
                    if (!getBoolean(key)) remove(key)
            },
        ))

        private fun encode(json: String): ByteArray {
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STATE_BYTES) { "EDIT_TOO_LARGE" }
            return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
        }

        private fun changedKeys(before: String, after: String): String {
            val old = JSONObject(before); val new = JSONObject(after)
            return org.json.JSONArray((old.keys().asSequence().toSet() + new.keys().asSequence().toSet())
                .filter { CanonicalJson.encode(old.opt(it)) != CanonicalJson.encode(new.opt(it)) }.sorted().take(12)).toString()
        }

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE edit_history_heads(edit_id TEXT PRIMARY KEY,asset_id TEXT NOT NULL REFERENCES assets(asset_id),generation INTEGER NOT NULL CHECK(generation>0),cursor INTEGER NOT NULL,earlier INTEGER NOT NULL CHECK(earlier>=0),format INTEGER NOT NULL,merge_key TEXT)")
            db.execSQL("CREATE TABLE edit_history_steps(edit_id TEXT NOT NULL,seq INTEGER NOT NULL,step_id TEXT NOT NULL,at_ms INTEGER NOT NULL,origin TEXT NOT NULL,label TEXT NOT NULL,payload BLOB NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(edit_id,seq))")
        }
    }
}
