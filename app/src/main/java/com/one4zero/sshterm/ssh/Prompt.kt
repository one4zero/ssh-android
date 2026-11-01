package com.one4zero.sshterm.ssh

import java.util.concurrent.CountDownLatch

/**
 * A question from a connection thread to the user (host key confirmation, password...).
 * The connection thread blocks in [await] until the UI calls [complete] or [cancel].
 */
class Prompt(
    val title: String,
    val message: String,
    val fields: List<Field> = emptyList(),
    val positive: String,
    val negative: String,
    /** Offer a "remember" checkbox (passwords). */
    val offerSave: Boolean = false,
    val warning: Boolean = false,
) {
    class Field(val label: String, val secret: Boolean)

    private val latch = CountDownLatch(1)

    @Volatile
    var accepted = false
        private set

    @Volatile
    var values: List<String> = emptyList()
        private set

    @Volatile
    var save = false
        private set

    val isDone: Boolean get() = latch.count == 0L

    fun complete(accepted: Boolean, values: List<String> = emptyList(), save: Boolean = false) {
        if (isDone) return
        this.accepted = accepted
        this.values = values
        this.save = save
        latch.countDown()
    }

    fun cancel() = complete(false)

    fun await(): Boolean {
        latch.await()
        return accepted
    }
}
