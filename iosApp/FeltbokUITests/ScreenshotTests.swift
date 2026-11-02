import XCTest

/// The App Store screenshots, the iOS twin of scripts/dev/screenshots.py. Run through
/// `just screenshots-ios`, which seeds the notes, fixes the status bar and location, and passes
/// SCREENSHOT_DIR; without it (a plain test run, CI) this skips.
final class ScreenshotTests: XCTestCase {
    private let app = XCUIApplication()
    private var dir: URL!

    override func setUpWithError() throws {
        let path = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"]
        try XCTSkipIf(path == nil, "only run by just screenshots-ios")
        dir = URL(fileURLWithPath: path!)
        continueAfterFailure = false
    }

    // Numbered like the Android set, so the stores show the same story in the same order.
    func testScreenshots() {
        relaunch()
        capture("1")

        tap("+")
        app.typeText("f")
        XCTAssertTrue(element("fiskemåke").waitForExistence(timeout: 10))
        capture("3")

        // A fresh launch is the simplest way back to the list: notes are saved, nothing else is.
        relaunch()
        tap("tjeld")
        XCTAssertTrue(element("Lagre").waitForExistence(timeout: 10))
        capture("4")

        tap("Lokalitet")
        XCTAssertTrue(element("Velg lokalitet").waitForExistence(timeout: 30))
        capture("2")

        relaunch()
        tap("Eksporter")
        XCTAssertTrue(element("Kopier observasjoner").waitForExistence(timeout: 10))
        capture("5")
    }

    private func relaunch() {
        app.terminate()
        app.launch()
        XCTAssertTrue(element("Eksporter").waitForExistence(timeout: 60), "the seeded list")
    }

    private func tap(_ label: String) {
        let e = element(label)
        XCTAssertTrue(e.waitForExistence(timeout: 10), label)
        e.tap()
    }

    /// Save the screen once two captures a second apart match, so map tiles and transitions finish.
    private func capture(_ name: String) {
        var prev: Data?
        var png = XCUIScreen.main.screenshot().pngRepresentation
        for _ in 0..<15 where png != prev {
            prev = png
            sleep(1)
            png = XCUIScreen.main.screenshot().pngRepresentation
        }
        XCTAssertNoThrow(try png.write(to: dir.appendingPathComponent("\(name).png")))
    }

    /// Compose's iOS accessibility tree exposes texts as static texts or buttons, so match any kind;
    /// a list row reads "1 tjeld NT", so match a whole word too.
    private func element(_ label: String) -> XCUIElement {
        let exact = app.descendants(matching: .any)[label].firstMatch
        if exact.exists { return exact }
        let word = NSPredicate(format: "label MATCHES %@", ".*\\b\(NSRegularExpression.escapedPattern(for: label))\\b.*")
        let partial = app.descendants(matching: .any).matching(word).firstMatch
        return partial.exists ? partial : exact
    }
}
