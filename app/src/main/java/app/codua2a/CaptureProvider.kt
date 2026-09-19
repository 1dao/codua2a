package app.codua2a

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Grants the camera access to one temporary capture, never the workspace or settings. */
class CaptureProvider : ContentProvider() {
    companion object {
        fun create(context: Context): Uri {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
            check(file.createNewFile())
            return Uri.Builder().scheme("content").authority("${context.packageName}.captures").appendPath(file.name).build()
        }
        fun file(context: Context, uri: Uri): File {
            require(uri.scheme == "content" && uri.authority == "${context.packageName}.captures" && uri.pathSegments.size == 1)
            val name = uri.pathSegments.single()
            require(name.matches(Regex("capture-[a-f0-9-]{36}\\.jpg")))
            val root = File(context.cacheDir, "captures").canonicalFile
            val file = File(root, name).canonicalFile
            require(file.parentFile == root)
            return file
        }
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String { file(requireNotNull(context), uri); return "image/jpeg" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = file(requireNotNull(context), uri)
        if (!target.isFile) throw FileNotFoundException("Capture expired")
        require(mode in listOf("r", "w", "wt", "rw", "rwt"))
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val target = file(requireNotNull(context), uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> target.name
            OpenableColumns.SIZE -> target.length()
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
