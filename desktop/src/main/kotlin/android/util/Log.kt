package android.util

/**
 * Remplaçant JVM de android.util.Log pour compiler MdrSession hors d'Android.
 * Les messages debug (trames TX/RX) ne sortent que si la propriété xmrip.verbose vaut "true".
 */
object Log {
    private val verbose = System.getProperty("xmrip.verbose") == "true"

    private val t0 = System.nanoTime()

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        if (verbose) System.err.println("+%5dms D/%s: %s".format((System.nanoTime() - t0) / 1_000_000, tag, msg))
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        System.err.println("W/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        System.err.println("E/$tag: $msg")
        return 0
    }
}
