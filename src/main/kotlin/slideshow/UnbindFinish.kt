package slideshow

import java.io.DataInputStream
import java.lang.invoke.MethodHandles

/**
 * Takes the `glFinish` out of OPENRNDR's render-target switch, for the show.
 *
 * **`RenderTargetGL3.unbind()` calls `Driver.instance.finish()` — `glFlush` and `glFinish` —
 * every time a target is let go**, unconditionally: in 0.4.5, and still in 0.5.0 and on master,
 * so an upgrade does not take it away. Every `isolatedWithTarget` therefore makes the CPU wait
 * until the GPU has finished everything queued so far, and the two never work at once. The
 * chapter card alone switches targets about fourteen times a frame. It is not the
 * `wait_for_finish` switch, which is a different wait inside the shape drawer.
 *
 * On native GL the wait buys nothing: commands on one context run in the order they were
 * given, so a target drawn into and then sampled is complete when it is sampled without
 * anyone waiting for it. The show runs native GL (`gl_type=gl` in `build.gradle.kts`).
 *
 * **It is done by defining the class before OPENRNDR loads it**, from the jar's own bytes with
 * the eleven bytes of that one call — load `Driver.Companion`, `getInstance()`, `finish()` —
 * turned into `nop`s. Nothing else in the class moves: the stack is empty before and after
 * the call and the code keeps its length, so no offset or stack map changes. The three
 * constant-pool entries are looked up by name rather than assumed, and the patch is applied
 * only where the call occurs exactly once; anything unexpected — another OPENRNDR, the class
 * already loaded — leaves OPENRNDR as it is and says so. It has to run before the first
 * window, which is why [present] calls it before `application {}`.
 */
object UnbindFinish {
    private const val CLASS = "org.openrndr.internal.gl3.RenderTargetGL3"

    /** Whether the patch took, for the startup line; null until [remove] has run. */
    var removed: Boolean? = null
        private set

    fun remove() {
        if (removed != null) return
        removed = runCatching { patch() }.getOrElse { e ->
            println("gl: kept OPENRNDR's glFinish on every render-target switch (${e.message})")
            false
        }
    }

    private fun patch(): Boolean {
        val loader = UnbindFinish::class.java.classLoader
        val original = loader.getResourceAsStream(CLASS.replace('.', '/') + ".class")?.use { it.readBytes() }
            ?: error("no $CLASS on the classpath")
        val patched = withoutFinish(original)
        // A lookup with package access in the class's own package and loader: any other class of
        // the package will do, so long as loading it does not load RenderTargetGL3 — an enum does not.
        val neighbour = Class.forName("org.openrndr.internal.gl3.DriverTypeGL", false, loader)
        MethodHandles.privateLookupIn(neighbour, MethodHandles.lookup()).defineClass(patched)
        println("gl: render-target switches no longer wait for the GPU (glFinish taken out of RenderTargetGL3.unbind)")
        return true
    }

    /** [bytes] with the one `Driver.instance.finish()` call turned into `nop`s. */
    internal fun withoutFinish(bytes: ByteArray): ByteArray {
        val pool = constantPool(bytes)
        fun ref(tag: Int, owner: String, name: String) = pool.entries.filter { (_, e) ->
            e.tag == tag && pool.className(e.a) == owner && pool.memberName(e.b) == name
        }.map { it.key }.singleOrNull() ?: error("no single $owner.$name in the constant pool")

        val companion = ref(9, "org/openrndr/internal/Driver", "Companion")                    // Fieldref
        val instance = ref(10, "org/openrndr/internal/Driver\$Companion", "getInstance")        // Methodref
        val finish = ref(11, "org/openrndr/internal/Driver", "finish")                          // InterfaceMethodref
        val call = byteArrayOf(
            0xB2.toByte(), (companion shr 8).toByte(), companion.toByte(),                     // getstatic
            0xB6.toByte(), (instance shr 8).toByte(), instance.toByte(),                       // invokevirtual
            0xB9.toByte(), (finish shr 8).toByte(), finish.toByte(), 1, 0                      // invokeinterface
        )
        val at = (0..bytes.size - call.size).filter { i -> call.indices.all { bytes[i + it] == call[it] } }
        require(at.size == 1) { "expected one finish() call, found ${at.size}" }
        return bytes.copyOf().also { out -> call.indices.forEach { out[at[0] + it] = 0 } }
    }

    private class Entry(val tag: Int, val a: Int = 0, val b: Int = 0, val text: String? = null)

    private class Pool(val entries: Map<Int, Entry>) {
        fun utf(i: Int) = entries[i]?.text
        fun className(i: Int) = entries[i]?.takeIf { it.tag == 7 }?.let { utf(it.a) }
        fun memberName(nameAndType: Int) = entries[nameAndType]?.takeIf { it.tag == 12 }?.let { utf(it.a) }
    }

    /** The class file's constant pool, enough of it to name fields and methods. */
    private fun constantPool(bytes: ByteArray): Pool {
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort(); input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val entries = HashMap<Int, Entry>()
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            entries[i] = when (tag) {
                1 -> Entry(tag, text = input.readUTF())
                3, 4 -> { input.readInt(); Entry(tag) }
                5, 6 -> { input.readLong(); Entry(tag) }
                7, 8, 16, 19, 20 -> Entry(tag, input.readUnsignedShort())
                9, 10, 11, 12, 17, 18 -> Entry(tag, input.readUnsignedShort(), input.readUnsignedShort())
                15 -> { input.readUnsignedByte(); Entry(tag, input.readUnsignedShort()) }
                else -> error("constant pool tag $tag")
            }
            i += if (tag == 5 || tag == 6) 2 else 1
        }
        return Pool(entries)
    }
}
