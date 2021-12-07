import java.text.SimpleDateFormat
import java.util.*

sealed class Logger {

    public abstract fun log(f: () -> String)

    object DebugLogger: Logger() {

        private val sdf = SimpleDateFormat("HH:mm:ss")

        override fun log(f: () -> String) {
            println(String.format("[%s] %s", sdf.format(Date()), f()))
        }
    }

    object NoneLogger: Logger() {
        override fun log(f: () -> String) { }
    }

}