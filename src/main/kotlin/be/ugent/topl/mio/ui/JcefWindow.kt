package be.ugent.topl.mio.ui

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import java.net.Socket
import javax.swing.JFrame
import kotlin.system.exitProcess

class JcefWindow private constructor(url: String) : JFrame("Multiverse Debugger") {
    init {
        val builder = CefAppBuilder()
        builder.cefSettings.windowless_rendering_enabled = false
        // Must use builder.setAppHandler instead of CefApp.addAppHandler, see jcefmaven docs.
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {
            override fun stateHasChanged(state: CefApp.CefAppState) {
                if (state == CefApp.CefAppState.TERMINATED) exitProcess(0)
            }
        })

        val cefApp = builder.build()
        val client = cefApp.createClient()
        val browser = client.createBrowser(url, false, false)

        contentPane.add(browser.uiComponent, BorderLayout.CENTER)
        setSize(1400, 900)
        setLocationRelativeTo(null)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                CefApp.getInstance().dispose()
                dispose()
            }
        })
        isVisible = true
    }

    companion object {
        // Netty starts asynchronously; block briefly until the port accepts
        // connections so the browser doesn't try to load before it's ready.
        private fun waitUntilListening(port: Int, timeoutMs: Long = 5000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket("localhost", port).close()
                    return
                } catch (_: IOException) {
                    Thread.sleep(50)
                }
            }
        }

        fun open(port: Int) {
            waitUntilListening(port)
            JcefWindow("http://localhost:$port")
        }
    }
}
