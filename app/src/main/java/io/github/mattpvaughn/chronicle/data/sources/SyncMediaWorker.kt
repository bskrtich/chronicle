package io.github.mattpvaughn.chronicle.data.sources

import android.content.Context
import androidx.work.*
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.getProgress
import io.github.mattpvaughn.chronicle.data.sources.local.LocalMediaParser
import io.github.mattpvaughn.chronicle.data.sources.local.LocalMediaSource
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.milliseconds
import kotlin.time.minutes

class SyncMediaWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    val trackRepository = Injector.get().trackRepo()
    val bookRepository = Injector.get().bookRepo()
    val prefsRepo = Injector.get().prefsRepo()

    override suspend fun doWork(): Result {

        Timber.i("SYNCING")

        val forceSync = inputData.getBoolean(FORCE_SYNC, false)

        val lastRefreshed = prefsRepo.lastRefreshTimeStamp.milliseconds
        val refreshMinTime = prefsRepo.refreshRateMinutes.minutes
        val refreshAt = (lastRefreshed - refreshMinTime).toLongMilliseconds()
        if (!forceSync && System.currentTimeMillis() < refreshAt) {
            return Result.success()
        }

        // Sync books on File System
        val sourceManager = Injector.get().sourceManager()
        val sources = sourceManager.getSources()
        sources.forEach { source ->
            val fetchedBooks = source.fetchBooks()
            val fetchedTracks = source.fetchTracks()

            if (fetchedBooks.isFailure || fetchedTracks.isFailure) {
                return@forEach
            }

            val tracks = fetchedTracks.getOrNull() ?: return@forEach
            val books = fetchedBooks.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: makeBooksFromTracks(source.id, tracks)

            val booksWithTrackInfo = books.map { book ->
                // [it.parentKey] to [book.id] constraint ensured by fetchBooks
                val tracksInAudiobook = tracks.filter { it.parentKey == book.id }
                book.copy(
                    progress = tracksInAudiobook.getProgress(),
                    duration = tracksInAudiobook.getDuration(),
                    leafCount = tracksInAudiobook.size.toLong()
                )
            }

            Timber.i("All generated books: ${booksWithTrackInfo.map { it.title }}")

            // merge book values into repos
            val isLocal = source is LocalMediaSource
            withContext(Dispatchers.IO) {
                bookRepository.upsert(source.id, booksWithTrackInfo, isLocal)
            }

            // Update all tracks here with IDs from the books
            withContext(Dispatchers.IO) {
                val updatedBooksMap = bookRepository
                    .getAudiobooksForSourceAsync(source.id, false)
                    .associateBy { it.title }

                val updatedIdTracks = tracks.map { track ->
                    val bookForTrack = updatedBooksMap[track.album]
                    if (bookForTrack != null) {
                        Timber.i("Got book for track! ${track.title}, ${track.media}")
                        track.copy(parentKey = bookForTrack.id)
                    } else {
                        Timber.i("No book for track! ${track.title}, ${track.album}")
                        track
                    }
                }

                trackRepository.upsert(source.id, updatedIdTracks)

                Timber.i("${bookRepository.getAllBooksAsync().map { "${it.title}, ${it.id}" }}")
                Timber.i(
                    "${
                        trackRepository.getAllTracksAsync().map { "${it.title}, ${it.parentKey}" }
                    }"
                )
            }


        }
        return Result.success()
    }

    private fun makeBooksFromTracks(
        sourceId: Long,
        tracks: List<MediaItemTrack>
    ): List<Audiobook> {
        return tracks.groupBy { it.parentKey }
            .mapNotNull { (bookId, tracks) -> makeAudiobook(tracks, sourceId, bookId) }
    }

    private fun makeAudiobook(
        tracksInAudiobook: List<MediaItemTrack>,
        sourceId: Long,
        bookId: Int
    ): Audiobook {
        val mostCommonBookName = tracksInAudiobook
            .groupBy { it.album }
            .mapValues { it.value.size }
            .maxByOrNull { it.value }?.key ?: ""
        val mostCommonArtist = tracksInAudiobook
            .groupBy { it.artist }
            .mapValues { it.value.size }
            .maxByOrNull { it.value }?.key ?: ""
        val mostCommonThumb = tracksInAudiobook
            .groupBy { it.thumb }
            .mapValues { it.value.size }
            .maxByOrNull { it.value }?.key ?: ""
        val mostCommonGenre = tracksInAudiobook
            .groupBy { it.thumb }
            .mapValues { it.value.size }
            .maxByOrNull { it.value }?.key ?: ""
        val duration = tracksInAudiobook.getDuration()
        return Audiobook(
            id = bookId,
            parentId = LocalMediaParser.ID_NOT_YET_SET,
            source = sourceId,
            title = mostCommonBookName,
            titleSort = mostCommonBookName,
            author = mostCommonArtist,
            thumb = mostCommonThumb,
            genre = mostCommonGenre,
            duration = duration,
            isCached = true,
            leafCount = tracksInAudiobook.size.toLong(),
        )
    }

    companion object {
        const val FORCE_SYNC = "SyncMediaWorker.FORCE_SYNC"

        fun makeData(forceSync: Boolean = false) = Data.Builder()
            .putBoolean(FORCE_SYNC, forceSync)
            .build()

    }
}