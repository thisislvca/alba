# Design: iCloud Photos for Android

Alba should feel like the app Apple would ship if iCloud Photos were an Android app: nothing about how it works should surprise an Android user, and nothing about how it looks should be mistaken for a Google app.

The rule: **Android's skeleton, iCloud's skin.** Structure and behaviour come from Material 3 and Android. Colour, type, shape, materials and motion carry the iCloud identity, through Material 3's own theming, not by replacing its components.

## Native Android: structure and behaviour

- Stock Material 3 components: text fields (errors in `supportingText`), buttons, dialogs, bottom sheets, snackbars, menus, floating toolbars, progress indicators. Wrap them for consistency; don't redraw them.
- System and predictive back, edge-to-edge, 48dp touch targets, Android haptics, the system text size.
- Adaptive layouts from `material3-adaptive`: list-detail and supporting panes on tablets, foldables and landscape (viewer Info as a side pane), a navigation rail on wide windows.
- One icon family: Material Symbols Rounded.
- Google Photos stays the reference for gestures, motion and small details in the gallery and viewer.
- No iOS idioms: no `>` chevrons, no small centred top-bar titles, no iOS switches, sheets or action sheets, no hairline-divided inset lists.

## iCloud: look and feel

- **Colour.** A complete static Material 3 scheme generated from an iCloud blue seed (Material Theme Builder), with every role set, so no Material default leaks. Primary is a deep enough blue to pass contrast; neutrals are cool and quiet; dark mode is near-black with surfaces that get lighter as they rise. Colour is used sparingly so photos stay the hero. Wallpaper (dynamic) colour is an opt-in setting, off by default.
- **Large titles.** Top-level screens open with a large, bold, start-aligned title (Library, Collections, Shared Albums) that collapses as you scroll: Material's large top app bar, styled from the type scale.
- **Type.** Material's type scale with the system font. Tracking is size-specific: tighter on display and headline roles, untouched on body and label roles. No ad-hoc sizes outside the theme.
- **Grouped lists.** Settings-style screens use Android 16 grouped list items: rounded groups separated by small gaps, no dividers, no chevrons.
- **Materials.** Chrome that floats over photos (viewer bars, the gallery toolbar) is translucent and blurred where Android supports it, solid where it doesn't or when the user reduces transparency.
- **Shape.** One generous, consistent corner scale from the theme's shapes.
- **Motion.** Springs from the theme's `MotionScheme`: critically damped by default, a little bounce only after a fling. Reduced motion becomes a cross-fade.
- **Brand moments.** Alba's dawn gradient appears where the brand speaks (the logo, sign-in, empty states), never as decoration on everyday screens.
- **Words.** Apple's names for iCloud data.
