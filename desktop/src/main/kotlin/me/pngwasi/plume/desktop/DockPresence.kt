package me.pngwasi.plume.desktop

/**
 * Plume is in the dock or taskbar exactly while its settings window is open. One rule, all three
 * platforms — but only one of them needs code to keep it.
 *
 * **Windows and Linux: nothing to do.** The window _is_ the taskbar entry. It appears when the
 * window opens and goes when it closes, because closing to the tray destroys the window rather than
 * hiding it. What Linux additionally needs is an identity the dock can match to the installed
 * launcher, and that is not a runtime decision: AWT derives `WM_CLASS` from the name of the class
 * holding the bottom stack frame and offers no supported way to change it, so the desktop entry
 * carries a `StartupWMClass` naming what AWT will produce. Without it the dock shows the pinned
 * launcher and an unmatched window side by side.
 *
 * **macOS needs the code.** A menu-bar app is an accessory process: it has no Dock entry at all,
 * and no amount of showing a window creates one. The activation policy has to change with the
 * window, which is [MacDock].
 */
object DockPresence {

    fun windowShown() {
        if (MacDock.isSupported) MacDock.showInDock()
    }

    fun windowHidden() {
        if (MacDock.isSupported) MacDock.hideFromDock()
    }
}
