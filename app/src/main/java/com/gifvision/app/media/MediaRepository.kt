package com.gifvision.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files
import com.gifvision.app.ui.state.StreamSelection
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale

/**
 * Abstraction over any persistence or file management operations required by the media pipeline.
 * UI components and the view-model should never touch raw file APIs; instead they communicate
 * through this repository so the implementation can swap between in-memory fakes, scoped storage,
 * or cloud-backed stores without rewriting business logic.
 */
interface MediaRepository {
    /**
     * Registers metadata for a user-selected source clip. Implementations can copy the file into
     * the app sandbox, index the original [Uri], or simply record bookkeeping information.
     */
    suspend fun registerSourceClip(layerId: Int, uri: Uri, displayName: String? = null): MediaSource

    /**
     * Persists a generated GIF for a specific stream. The coordinator passes the FFmpeg output
     * path which the repository may rewrite (e.g., moving into scoped storage) before returning the
     * canonical asset reference to the caller.
     */
    suspend fun storeStreamOutput(
        layerId: Int,
        stream: StreamSelection,
        suggestedPath: String?
    ): MediaAsset

    /** Stores the blended output for an entire layer (Stream A + Stream B). */
    suspend fun storeLayerBlend(layerId: Int, suggestedPath: String?): MediaAsset

    /** Stores the final master blend that combines Layer 1 and Layer 2. */
    suspend fun storeMasterBlend(suggestedPath: String?): MediaAsset

    /** Retrieves the most recently registered source clip for a layer, if available. */
    suspend fun getSourceClip(layerId: Int): MediaSource?

    /** Copies the provided file into the public Downloads directory, handling legacy storage fallbacks pre-Android 10. */
    suspend fun exportToDownloads(sourcePath: String, displayName: String): Uri
}

/**
 * Value object describing a stored or registered media artifact. The optional identifiers allow
 * the caller to determine whether the asset belongs to a stream, a layer blend, or the master mix.
 */
data class MediaAsset(
    val id: String = UUID.randomUUID().toString(),
    val layerId: Int? = null,
    val stream: StreamSelection? = null,
    val path: String,
    val displayName: String,
    val recordedAtEpochMillis: Long = System.currentTimeMillis()
)

/** Metadata for an imported source clip. */
data class MediaSource(
    val layerId: Int,
    val displayName: String,
    val uri: Uri,
    val importedAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Lightweight in-memory repository used by the preview build. It keeps media references in maps
 * so the rest of the stack can be exercised without writing to disk. The implementation is thread
 * safe which allows the view-model to call into it from different coroutines.
 */
class InMemoryMediaRepository : MediaRepository {
    private val mutex = Mutex()
    private val sources = mutableMapOf<Int, MediaSource>()
    private val streamOutputs = mutableMapOf<Pair<Int, StreamSelection>, MediaAsset>()
    private val layerOutputs = mutableMapOf<Int, MediaAsset>()
    private var masterOutput: MediaAsset? = null

    override suspend fun registerSourceClip(
        layerId: Int,
        uri: Uri,
        displayName: String?
    ): MediaSource {
        val source = MediaSource(
            layerId = layerId,
            displayName = displayName ?: uri.lastPathSegment ?: uri.toString(),
            uri = uri
        )
        mutex.withLock { sources[layerId] = source }
        return source
    }

    override suspend fun storeStreamOutput(
        layerId: Int,
        stream: StreamSelection,
        suggestedPath: String?
    ): MediaAsset {
        val asset = MediaAsset(
            layerId = layerId,
            stream = stream,
            path = suggestedPath ?: "layer_${layerId}_stream_${stream.name.lowercase()}_${UUID.randomUUID()}.gif",
            displayName = "Layer ${layerId} • Stream ${stream.name}"
        )
        mutex.withLock { streamOutputs[layerId to stream] = asset }
        return asset
    }

    override suspend fun storeLayerBlend(layerId: Int, suggestedPath: String?): MediaAsset {
        val asset = MediaAsset(
            layerId = layerId,
            path = suggestedPath ?: "layer_${layerId}_blend_${UUID.randomUUID()}.gif",
            displayName = "Layer ${layerId} Blend"
        )
        mutex.withLock { layerOutputs[layerId] = asset }
        return asset
    }

    override suspend fun storeMasterBlend(suggestedPath: String?): MediaAsset {
        val asset = MediaAsset(
            path = suggestedPath ?: "master_blend_${UUID.randomUUID()}.gif",
            displayName = "Master Blend"
        )
        mutex.withLock { masterOutput = asset }
        return asset
    }

    override suspend fun getSourceClip(layerId: Int): MediaSource? = mutex.withLock { sources[layerId] }

    override suspend fun exportToDownloads(sourcePath: String, displayName: String): Uri {
        return Uri.parse(sourcePath)
    }
}

/**
 * Scoped storage backed repository that persists all intermediate outputs and metadata in the
 * application's private directories. Every stored asset writes a companion JSON file so future
 * sessions can instantly discover what was rendered, when it completed, and which layer/stream it
 * belongs to.
 */
class ScopedStorageMediaRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MediaRepository {
    private val mutex = Mutex()
    private val sources = mutableMapOf<Int, MediaSource>()
    private val streamOutputs = mutableMapOf<Pair<Int, StreamSelection>, MediaAsset>()
    private val layerOutputs = mutableMapOf<Int, MediaAsset>()
    private var masterOutput: MediaAsset? = null

    private val rootDirectory: File = File(context.filesDir, "media_store").apply { mkdirs() }
    private val sourcesDirectory = File(rootDirectory, "sources").apply { mkdirs() }
    private val streamsDirectory = File(rootDirectory, "streams").apply { mkdirs() }
    private val blendsDirectory = File(rootDirectory, "blends").apply { mkdirs() }

    init {
        loadExistingMetadata()
    }

    override suspend fun registerSourceClip(layerId: Int, uri: Uri, displayName: String?): MediaSource =
        withContext(dispatcher) {
            val resolvedName = displayName ?: uri.lastPathSegment ?: "layer_${layerId}_source"
            val sanitized = resolvedName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val destination = File(sourcesDirectory, "layer_${layerId}_$sanitized")
            copyUriToFile(uri, destination)
            val source = MediaSource(
                layerId = layerId,
                displayName = sanitized,
                uri = Uri.fromFile(destination)
            )
            persistMetadata(destination, metadata = JSONObject().apply {
                put("type", "source")
                put("layerId", layerId)
                put("displayName", sanitized)
                put("path", destination.absolutePath)
                put("uri", source.uri.toString())
                put("importedAt", source.importedAtEpochMillis)
            })
            mutex.withLock { sources[layerId] = source }
            source
        }

    override suspend fun storeStreamOutput(
        layerId: Int,
        stream: StreamSelection,
        suggestedPath: String?
    ): MediaAsset = withContext(dispatcher) {
        val fileName = "layer_${layerId}_stream_${stream.name.lowercase(Locale.ROOT)}_${System.currentTimeMillis()}.gif"
        val destination = File(streamsDirectory, fileName)
        copyPathToFile(suggestedPath, destination)
        val asset = MediaAsset(
            layerId = layerId,
            stream = stream,
            path = destination.absolutePath,
            displayName = "Layer ${layerId} • Stream ${stream.name}"
        )
        persistMetadata(destination, JSONObject().apply {
            put("type", "stream")
            put("id", asset.id)
            put("layerId", layerId)
            put("stream", stream.name)
            put("path", asset.path)
            put("displayName", asset.displayName)
            put("recordedAt", asset.recordedAtEpochMillis)
        })
        mutex.withLock { streamOutputs[layerId to stream] = asset }
        asset
    }

    override suspend fun storeLayerBlend(layerId: Int, suggestedPath: String?): MediaAsset =
        withContext(dispatcher) {
            val fileName = "layer_${layerId}_blend_${System.currentTimeMillis()}.gif"
            val destination = File(blendsDirectory, fileName)
            copyPathToFile(suggestedPath, destination)
            val asset = MediaAsset(
                layerId = layerId,
                path = destination.absolutePath,
                displayName = "Layer ${layerId} Blend"
            )
            persistMetadata(destination, JSONObject().apply {
                put("type", "layerBlend")
                put("id", asset.id)
                put("layerId", layerId)
                put("path", asset.path)
                put("displayName", asset.displayName)
                put("recordedAt", asset.recordedAtEpochMillis)
            })
            mutex.withLock { layerOutputs[layerId] = asset }
            asset
        }

    override suspend fun storeMasterBlend(suggestedPath: String?): MediaAsset = withContext(dispatcher) {
        val fileName = "master_blend_${System.currentTimeMillis()}.gif"
        val destination = File(blendsDirectory, fileName)
        copyPathToFile(suggestedPath, destination)
        val asset = MediaAsset(
            path = destination.absolutePath,
            displayName = "Master Blend"
        )
        persistMetadata(destination, JSONObject().apply {
            put("type", "masterBlend")
            put("id", asset.id)
            put("path", asset.path)
            put("displayName", asset.displayName)
            put("recordedAt", asset.recordedAtEpochMillis)
        })
        mutex.withLock { masterOutput = asset }
        asset
    }

    override suspend fun getSourceClip(layerId: Int): MediaSource? = mutex.withLock { sources[layerId] }

    override suspend fun exportToDownloads(sourcePath: String, displayName: String): Uri = withContext(dispatcher) {
        val file = File(sourcePath)
        if (!file.exists()) {
            throw FileNotFoundException("Cannot export missing file at $sourcePath")
        }
        val resolver = context.contentResolver
        val targetName = if (displayName.endsWith(".gif", ignoreCase = true)) displayName else "$displayName.gif"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            put(MediaStore.MediaColumns.SIZE, file.length())
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            val legacyDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!legacyDownloads.exists()) {
                legacyDownloads.mkdirs()
            }
            val legacyFile = File(legacyDownloads, targetName)
            if (legacyFile.exists()) {
                legacyFile.delete()
            }
            values.put(MediaStore.MediaColumns.DATA, legacyFile.absolutePath)
            Files.getContentUri("external")
        }
        val destination = resolver.insert(collection, values)
            ?: throw IllegalStateException("Unable to create Downloads entry")
        try {
            resolver.openOutputStream(destination)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open output stream for $destination")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalizeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(destination, finalizeValues, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
        destination
    }

    private fun copyUriToFile(uri: Uri, destination: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open input stream for $uri")
    }

    private fun copyPathToFile(path: String?, destination: File) {
        if (path.isNullOrBlank()) return
        val source = File(path)
        if (!source.exists()) return
        if (source.absolutePath == destination.absolutePath) return
        source.copyTo(destination, overwrite = true)
    }

    private fun persistMetadata(target: File, metadata: JSONObject) {
        val metadataFile = File(target.parentFile ?: rootDirectory, "${target.nameWithoutExtension}.json")
        metadataFile.writeText(metadata.toString())
    }

    private fun loadExistingMetadata() {
        loadSourceMetadata()
        loadStreamMetadata()
        loadBlendMetadata()
    }

    private fun loadSourceMetadata() {
        sourcesDirectory.listFiles { file -> file.extension.equals("json", ignoreCase = true) }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val layerId = json.optInt("layerId")
                val uri = Uri.parse(json.optString("uri"))
                val displayName = json.optString("displayName")
                val importedAt = json.optLong("importedAt", System.currentTimeMillis())
                sources[layerId] = MediaSource(
                    layerId = layerId,
                    displayName = displayName,
                    uri = uri,
                    importedAtEpochMillis = importedAt
                )
            }
        }
    }

    private fun loadStreamMetadata() {
        streamsDirectory.listFiles { file -> file.extension.equals("json", ignoreCase = true) }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val layerId = json.optInt("layerId")
                val stream = StreamSelection.valueOf(json.optString("stream", StreamSelection.A.name))
                val asset = MediaAsset(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    layerId = layerId,
                    stream = stream,
                    path = json.optString("path"),
                    displayName = json.optString("displayName"),
                    recordedAtEpochMillis = json.optLong("recordedAt", System.currentTimeMillis())
                )
                streamOutputs[layerId to stream] = asset
            }
        }
    }

    private fun loadBlendMetadata() {
        blendsDirectory.listFiles { file -> file.extension.equals("json", ignoreCase = true) }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                when (json.optString("type")) {
                    "layerBlend" -> {
                        val layerId = json.optInt("layerId")
                        val asset = MediaAsset(
                            id = json.optString("id", UUID.randomUUID().toString()),
                            layerId = layerId,
                            path = json.optString("path"),
                            displayName = json.optString("displayName"),
                            recordedAtEpochMillis = json.optLong("recordedAt", System.currentTimeMillis())
                        )
                        layerOutputs[layerId] = asset
                    }
                    "masterBlend" -> {
                        masterOutput = MediaAsset(
                            id = json.optString("id", UUID.randomUUID().toString()),
                            path = json.optString("path"),
                            displayName = json.optString("displayName"),
                            recordedAtEpochMillis = json.optLong("recordedAt", System.currentTimeMillis())
                        )
                    }
                }
            }
        }
    }
}
