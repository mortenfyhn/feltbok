import XCTest

/// Smoke test for the shared Kotlin UI on iOS: the app launches into the list and the picker map
/// opens without crashing. Those are the two places a dependency problem has crashed the app before
/// (a kotlinx-datetime mismatch at launch, MapLibre's missing resources on the map), and neither
/// shows up in the Android build or the unit tests.
final class LaunchTests: XCTestCase {
    func testLaunchesAndOpensTheMap() {
        let app = XCUIApplication()
        // The location prompt, in an English or a Norwegian simulator. It's handled when the next
        // interaction finds it in the way (the swipe below).
        addUIInterruptionMonitor(withDescription: "Location permission") { alert in
            for label in ["Allow While Using App", "Tillat mens appen er i bruk"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
        app.launch()
        XCTAssertTrue(element(app, "Arkiv").waitForExistence(timeout: 60), "the list's footer")

        app.swipeUp() // harmless on the list; clears the location prompt if it's up

        // The status strip at the top opens the locality picker.
        let strip = app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.11))
        strip.tap()
        if !element(app, "Velg lokalitet").waitForExistence(timeout: 10) { strip.tap() }
        XCTAssertTrue(element(app, "Velg lokalitet").waitForExistence(timeout: 30), "the picker's header")
        sleep(5) // let MapLibre load its style, tiles and resources
        XCTAssertEqual(app.state, .runningForeground)
        XCTAssertTrue(element(app, "Velg lokalitet").exists)
    }

    /// Compose's iOS accessibility tree exposes texts as static texts or buttons, so match any kind.
    private func element(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.descendants(matching: .any)[label].firstMatch
    }
}
