# Woodle Screw Auto Solver — Android prototype

This is a personal-use Android prototype that:

1. requests screen-capture permission,
2. watches the Woodle Screw screen,
3. detects the two current target colors,
4. searches the board for screw-head candidates,
5. chooses a screw whose color matches a target,
6. taps it through an Android AccessibilityService,
7. waits for animation, rescans, and repeats.

## Important

This is a **v1 heuristic solver** built from the gameplay sample supplied in the chat.
It will need tuning on the actual phone because Woodle Screw levels, screen ratios,
themes, ads, animations, and screw artwork can vary.

It does **not** contain ad-clicking logic.

## Build

Open this folder in Android Studio and let Gradle sync.

Minimum Android version: Android 7.0 / API 24, because programmatic accessibility
gestures (`dispatchGesture`) were added in API 24.

## Use on phone

1. Install the app.
2. Open **Woodle Solver**.
3. Tap **Enable Tap Service**.
4. In Android Accessibility settings, enable **Woodle Solver**.
5. Return to the app.
6. Tap **Start Solver**.
7. Accept Android's screen-capture prompt.
8. Switch to **Woodle Screw**.

The foreground notification shows basic detector status.

To stop it, return to Woodle Solver and tap **Stop Solver**.

## Current detector assumptions

- portrait orientation,
- the two active target boxes are near the upper part of the display,
- the puzzle occupies the middle ~60% of the screen,
- screw heads are roughly circular and have a darker slot/cross in the center,
- screw colors are close in hue to the active collector colors.

## What to tune next

After testing on the user's real phone:
- collector box bounds,
- board bounds,
- screw radius range,
- color tolerance,
- false-positive rejection,
- completed-level / Continue detection,
- pop-up rejection.
