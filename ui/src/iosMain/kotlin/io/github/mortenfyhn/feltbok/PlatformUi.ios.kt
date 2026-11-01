@file:OptIn(ExperimentalForeignApi::class)

package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIDatePicker
import platform.UIKit.UIDatePickerMode
import platform.UIKit.UIDatePickerStyle
import platform.UIKit.UIDevice
import platform.UIKit.UIFont
import platform.UIKit.UILayoutConstraintAxisHorizontal
import platform.UIKit.UILayoutConstraintAxisVertical
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UIStackView
import platform.UIKit.UIStackViewDistributionEqualSpacing
import platform.UIKit.UIViewController
import platform.UIKit.sheetPresentationController
import platform.UIKit.systemBackgroundColor

private object IosPlatformUi : PlatformUi {
    override val deviceInfo: String get() = "Apple ${UIDevice.currentDevice.model}\niOS ${UIDevice.currentDevice.systemVersion}"

    override fun openUrl(url: String) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }

    override fun sendMail(uri: String) = openUrl(uri)

    override fun toast(message: String) = IosToast.show(message, long = false)

    override fun pickDate(initialMs: Long, onPick: (Long) -> Unit, clearLabel: String?, onClear: () -> Unit) =
        showPicker(initialMs, UIDatePickerMode.UIDatePickerModeDate, clearLabel?.let { it to onClear }) { picked ->
            onPick(merge(dateFrom = picked, timeFrom = initialMs))
        }

    override fun pickTime(initialMs: Long, onPick: (Long) -> Unit) =
        showPicker(initialMs, UIDatePickerMode.UIDatePickerModeTime, null) { picked ->
            onPick(merge(dateFrom = initialMs, timeFrom = picked))
        }
}

@Composable
actual fun rememberPlatformUi(): PlatformUi = IosPlatformUi

/** iOS's own date/time picker in a sheet: inline calendar for a date, wheels for a time. */
private fun showPicker(ms: Long, mode: UIDatePickerMode, extra: Pair<String, () -> Unit>?, onPicked: (Long) -> Unit) {
    val vc = UIViewController()
    vc.view.backgroundColor = UIColor.systemBackgroundColor
    // The app's brand green (Brand.primary) instead of iOS blue, like the Android dialogs' accent.
    vc.view.tintColor = UIColor(red = 0x5B / 255.0, green = 0x7A / 255.0, blue = 0x2B / 255.0, alpha = 1.0)
    val picker = UIDatePicker().apply {
        datePickerMode = mode
        preferredDatePickerStyle = if (mode == UIDatePickerMode.UIDatePickerModeDate) {
            UIDatePickerStyle.UIDatePickerStyleInline
        } else {
            UIDatePickerStyle.UIDatePickerStyleWheels
        }
        locale = NSLocale(localeIdentifier = "nb_NO")
        date = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)
    }
    fun button(title: String, bold: Boolean = false, action: () -> Unit) =
        UIButton.buttonWithType(UIButtonTypeSystem).apply {
            setTitle(title, UIControlStateNormal)
            if (bold) titleLabel?.font = UIFont.boldSystemFontOfSize(17.0)
            addAction(UIAction.actionWithHandler { _ -> action() }, UIControlEventTouchUpInside)
        }
    val buttons = UIStackView().apply {
        axis = UILayoutConstraintAxisHorizontal
        distribution = UIStackViewDistributionEqualSpacing
        addArrangedSubview(button(Strings.cancel) { vc.dismissViewControllerAnimated(true, null) })
        extra?.let { (title, act) -> addArrangedSubview(button(title) { act(); vc.dismissViewControllerAnimated(true, null) }) }
        addArrangedSubview(
            button(Strings.Time.done, bold = true) {
                onPicked((picker.date.timeIntervalSince1970 * 1000).toLong())
                vc.dismissViewControllerAnimated(true, null)
            },
        )
    }
    val stack = UIStackView().apply {
        axis = UILayoutConstraintAxisVertical
        spacing = 12.0
        translatesAutoresizingMaskIntoConstraints = false
        addArrangedSubview(buttons)
        addArrangedSubview(picker)
    }
    vc.view.addSubview(stack)
    NSLayoutConstraint.activateConstraints(
        listOf(
            stack.topAnchor.constraintEqualToAnchor(vc.view.safeAreaLayoutGuide.topAnchor, 16.0),
            stack.leadingAnchor.constraintEqualToAnchor(vc.view.leadingAnchor, 20.0),
            stack.trailingAnchor.constraintEqualToAnchor(vc.view.trailingAnchor, -20.0),
        ),
    )
    vc.sheetPresentationController?.detents = listOf(
        if (mode == UIDatePickerMode.UIDatePickerModeDate) {
            UISheetPresentationControllerDetent.largeDetent()
        } else {
            UISheetPresentationControllerDetent.mediumDetent()
        },
    )
    topViewController()?.presentViewController(vc, true, null)
}

/** [dateFrom]'s date with [timeFrom]'s time of day, in the device's time zone. */
private fun merge(dateFrom: Long, timeFrom: Long): Long {
    val cal = NSCalendar.currentCalendar
    val d = cal.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, NSDate.dateWithTimeIntervalSince1970(dateFrom / 1000.0))
    val t = cal.components(NSCalendarUnitHour or NSCalendarUnitMinute, NSDate.dateWithTimeIntervalSince1970(timeFrom / 1000.0))
    d.hour = t.hour
    d.minute = t.minute
    return ((cal.dateFromComponents(d)?.timeIntervalSince1970 ?: (dateFrom / 1000.0)) * 1000).toLong()
}

internal fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}
