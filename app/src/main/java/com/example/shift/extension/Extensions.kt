package com.example.shift.extension

import android.util.Log
import com.example.shift.data.CrashLogger
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Consumes exceptions that escape an extension coroutine.
 *
 * Without this, a throw inside any `launch` reaches the thread's default handler and
 * kills the whole process — the "app has stopped" dialog on the Karoo, mid-ride.
 * A blank data field is a far better outcome than a dead app.
 */
val extensionExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e("ShiftExtension", "Uncaught exception in extension coroutine", throwable)
    CrashLogger.record(throwable, "extension coroutine")
}

/**
 * Runs [block], recording and swallowing any failure. Used per-tick inside state
 * collectors so one bad frame does not tear down the collector.
 */
inline fun guarded(context: String, block: () -> Unit) {
    try {
        block()
    } catch (c: CancellationException) {
        throw c // never swallow cancellation, it would leak the collector
    } catch (t: Throwable) {
        Log.e("ShiftExtension", "Error in $context", t)
        CrashLogger.record(t, context)
    }
}

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> {
    return callbackFlow {
        val listenerId = addConsumer<T> {
            trySend(it)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}
