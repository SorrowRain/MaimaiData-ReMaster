import AuthenticationServices
import CryptoKit
import Photos
import Security
import SwiftUI
import UIKit

enum PhotoLibrarySaver {
    static func save(_ image: UIImage) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw NSError(domain: "MaimaiData", code: 1, userInfo: [NSLocalizedDescriptionKey: "请在系统设置中允许访问相册权限"])
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct ZoomableImageScrollView: UIViewRepresentable {
    let image: UIImage

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.delegate = context.coordinator
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = 4.5
        scrollView.bouncesZoom = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.backgroundColor = .clear

        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(imageView)

        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor)
        ])

        context.coordinator.imageView = imageView
        context.coordinator.scrollView = scrollView

        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        return scrollView
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {
        if context.coordinator.imageView?.image !== image {
            context.coordinator.imageView?.image = image
            uiView.setZoomScale(1.0, animated: false)
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        weak var scrollView: UIScrollView?

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            imageView
        }

        @objc func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
            guard let scrollView, let imageView else { return }
            if scrollView.zoomScale > 1.05 {
                scrollView.setZoomScale(1.0, animated: true)
            } else {
                let point = gesture.location(in: imageView)
                let targetScale: CGFloat = 2.6
                let width = scrollView.bounds.width / targetScale
                let height = scrollView.bounds.height / targetScale
                let rect = CGRect(x: point.x - width / 2, y: point.y - height / 2, width: width, height: height)
                scrollView.zoom(to: rect, animated: true)
            }
        }
    }
}

/// Adds a subtle GPU cross-dissolve to the enclosing `UITabBarController`'s content view
/// when switching tabs, keeping rendered buffers 100% opaque (preventing any white window flash)
/// without touching `UITabBarController.delegate` or the native Liquid Glass `UITabBar`.
struct TabCrossfadeBridge: UIViewControllerRepresentable {
    let selectedTab: Int

    func makeCoordinator() -> Coordinator {
        Coordinator(lastTab: selectedTab)
    }

    func makeUIViewController(context: Context) -> ProbeViewController {
        let vc = ProbeViewController()
        vc.onResolveTabBarController = { [weak coordinator = context.coordinator] tbc in
            coordinator?.tabBarController = tbc
        }
        return vc
    }

    func updateUIViewController(_ uiViewController: ProbeViewController, context: Context) {
        if let tbc = uiViewController.tabBarController {
            context.coordinator.tabBarController = tbc
        }
        guard context.coordinator.lastTab != selectedTab else { return }
        context.coordinator.lastTab = selectedTab

        guard !UIAccessibility.isReduceMotionEnabled,
              let tbc = context.coordinator.tabBarController ?? uiViewController.tabBarController else {
            return
        }
        let contentView = tbc.view.subviews.first { !($0 is UITabBar) } ?? tbc.view
        let transition = CATransition()
        transition.type = .fade
        transition.duration = 0.15
        transition.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        contentView?.layer.add(transition, forKey: "maimaiTabCrossfade")
    }

    final class Coordinator {
        var lastTab: Int
        weak var tabBarController: UITabBarController?

        init(lastTab: Int) {
            self.lastTab = lastTab
        }
    }

    final class ProbeViewController: UIViewController {
        var onResolveTabBarController: ((UITabBarController) -> Void)?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.isHidden = true
            view.isUserInteractionEnabled = false
        }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            if let tbc = tabBarController {
                onResolveTabBarController?(tbc)
            }
        }
    }
}

enum WebAuthSessionError: LocalizedError {
    case cancelled
    case missingCallbackURL
    case failedToStart

    var errorDescription: String? {
        switch self {
        case .cancelled:
            return nil
        case .missingCallbackURL:
            return "未收到授权回调地址"
        case .failedToStart:
            return "无法启动系统授权窗口，请稍后重试"
        }
    }
}

/// Runs an OAuth 2.0 `ASWebAuthenticationSession` bound to the active key window.
@MainActor
final class WebAuthSessionRunner: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var activeSession: ASWebAuthenticationSession?
    private var activeContinuation: CheckedContinuation<URL, Error>?

    func authenticate(url: URL, callbackScheme: String) async throws -> URL {
        cancelActiveSession()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.activeContinuation = continuation
                let session = ASWebAuthenticationSession(
                    url: url,
                    callbackURLScheme: callbackScheme
                ) { [weak self] callbackURL, error in
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        if let error {
                            if let authError = error as? ASWebAuthenticationSessionError,
                               authError.code == .canceledLogin {
                                self.finish(with: .failure(WebAuthSessionError.cancelled))
                            } else {
                                self.finish(with: .failure(error))
                            }
                            return
                        }
                        guard let callbackURL else {
                            self.finish(with: .failure(WebAuthSessionError.missingCallbackURL))
                            return
                        }
                        self.finish(with: .success(callbackURL))
                    }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                self.activeSession = session
                if !session.start() {
                    self.finish(with: .failure(WebAuthSessionError.failedToStart))
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.cancelActiveSession()
            }
        }
    }

    private func cancelActiveSession() {
        let session = activeSession
        activeSession = nil
        session?.cancel()
        if let continuation = activeContinuation {
            activeContinuation = nil
            continuation.resume(throwing: WebAuthSessionError.cancelled)
        }
    }

    private func finish(with result: Result<URL, Error>) {
        activeSession = nil
        guard let continuation = activeContinuation else { return }
        activeContinuation = nil
        continuation.resume(with: result)
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let keyWindow = scenes
            .first(where: { $0.activationState == .foregroundActive })?
            .windows
            .first(where: \.isKeyWindow) {
            return keyWindow
        }
        if let anyWindow = scenes.flatMap(\.windows).first {
            return anyWindow
        }
        if let scene = scenes.first {
            return UIWindow(windowScene: scene)
        }
        return ASPresentationAnchor()
    }
}

enum PKCEHelper {
    static func randomURLSafeString(byteCount: Int = 32) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URLEncode(Data(bytes))
    }

    static func generateCodeVerifier() -> String {
        randomURLSafeString(byteCount: 48)
    }

    static func codeChallengeS256(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return base64URLEncode(Data(digest))
    }

    private static func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
