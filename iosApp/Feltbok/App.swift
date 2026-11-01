import SwiftUI
import FeltbokUi

@main
struct FeltbokApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.all)
        }
    }
}

/// The shared Compose UI (ui/src/commonMain), in a UIKit view controller.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: UIViewControllerRepresentableContext<ComposeView>) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: UIViewControllerRepresentableContext<ComposeView>) {}
}
