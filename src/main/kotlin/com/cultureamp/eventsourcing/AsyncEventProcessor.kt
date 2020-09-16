package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import kotlin.random.Random

class AsyncEventProcessor(
    val eventSource: EventSource,
    val bookmarkStore: BookmarkStore,
    val bookmarkName: String,
    val eventProcessor: EventProcessor,
    private val batchSize: Int = 1000,
    private val startLog: (Bookmark) -> Unit = { bookmark ->
        System.out.println("Polling for events for ${bookmark.name} from sequence ${bookmark.sequence}")
    },
    private val endLog: (Int, Bookmark) -> Unit = { count, bookmark ->
        if (count > 0 || Random.nextFloat() < 0.01) {
            System.out.println("Finished processing batch for ${bookmark.name}, ${count} events up to sequence ${bookmark.sequence}")
        }
    }
) {

    fun processOneBatch(): Action {
        val startBookmark = bookmarkStore.bookmarkFor(bookmarkName)

        startLog(startBookmark)

        val (count, finalBookmark) = eventSource.getAfter(startBookmark.sequence, eventProcessor.eventClasses, batchSize).foldIndexed(
            0 to startBookmark
        ) { index, _, sequencedEvent ->
            eventProcessor.process(sequencedEvent.event)
            val updatedBookmark = startBookmark.copy(sequence = sequencedEvent.sequence)
            bookmarkStore.save(updatedBookmark)
            index + 1 to updatedBookmark
        }

        endLog(count, finalBookmark)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }
}
