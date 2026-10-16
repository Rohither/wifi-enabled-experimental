import SwiftUI
#if os(macOS)
import AppKit

final class NeXConnectMacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        guard let path = Bundle.main.path(forResource: "DockIcon", ofType: "png"),
              let icon = NSImage(contentsOfFile: path) else { return }
        NSApplication.shared.applicationIconImage = icon
    }
}
#endif

@main
struct NeXConnectApp: App {
#if os(macOS)
    @NSApplicationDelegateAdaptor(NeXConnectMacAppDelegate.self) private var appDelegate
#endif

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
