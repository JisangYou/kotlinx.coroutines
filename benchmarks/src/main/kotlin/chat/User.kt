package chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import java.util.*
import java.util.concurrent.ThreadLocalRandom

/**
 * An abstract class for server-side chat users.
 * Every user has its own channel through which users can get messages from other users [receiveAndProcessMessage],
 * users can send messages to their friends' channels as well [sendMessage].
 * User contains sent and received messages metrics.
 * To emulate real world chat servers, some work will be executed on CPU during sending and receiving messages. When user
 * connects to the server, the connection itself consumes some CPU time.
 * At the end of the benchmark execution [stopUser] should be called.
 */
abstract class User(val id: Long,
                    val activity : Double,
                    val messageChannel: Channel<Message>,
                    private val configuration: BenchmarkConfiguration) {
    var sentMessages = 0L
        protected set

    var receivedMessages = 0L
        protected set

    protected val random = Random(id)

    private var messagesToSent : Double = 0.0

    @Volatile
    private var stopped = false

    fun startUser() {
        messagesToSent += activity
        var count = 0L
        CoroutineScope(context).launch {
            while (messagesToSent >= 1 && !stopped) {
                sendMessage()
                messagesToSent--
                if (count++ % 61 == 5L) {
                    yield()
                }
            }
            while (!stopped) {
                val message = messageChannel.receiveOrClosed().valueOrNull ?: break
                messagesToSent += activity
                receiveAndProcessMessage(message)
                while (messagesToSent >= 1) {
                    sendMessage()
                    messagesToSent--
                }
                if (count++ % 61 == 5L) {
                    yield()
                }
            }
        }
    }

    private fun doSomeWorkOnCpu() {
        // We use geometric distribution here
        val p = 1.0 / configuration.averageWork
        val r = ThreadLocalRandom.current()
        while (true) {
            if (r.nextDouble() < p) break
        }
    }

    private suspend fun sendMessage() {
        val userChannelToSend = chooseChannelToSend() ?: return

        val now = System.nanoTime()
        try {
            select<Unit> {
                messageChannel.onReceiveOrClosed { message ->
                    run {
                        if (!message.isClosed) {
                            receiveAndProcessMessage(message.value)
                        }
                    }
                }
                userChannelToSend.onSend(Message(id, now)) {
                    if (!stopped) {
                        sentMessages++
                    }
                    doSomeWorkOnCpu()
                }
            }
        } catch (ignored : ClosedSendChannelException) {}
          catch (ignored : IllegalStateException) {}
    }

    private fun receiveAndProcessMessage(message : Message) {
        if (!stopped) {
            receivedMessages++
        }
        doSomeWorkOnCpu()
    }

    fun stopUser() {
        stopped = true
        messageChannel.close()
    }

    abstract fun chooseChannelToSend() : Channel<Message>?
}

/**
 * A message from one of the users to another one
 */
class Message(private val userId : Long, val nanos : Long)