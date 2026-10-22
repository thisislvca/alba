# Design direction: Android-native, Apple-familiar

> **Superseded on 2026-10-22.** Alba is not being redesigned. It keeps its current layout and is polished to match Google Photos' gestures, motion and details (see `VISION.md`). This document is kept for reference only.

Alba should look and behave like a native Android app, organised the way iCloud users know from Apple Photos.

- **From Apple, take the structure.** That means where things are, what they are called, and how the library is grouped.
- **From Material 3, take everything the user touches or sees.** That covers navigation, bars, lists, sheets, dialogs, selection, motion, colour and type.

## Why

A hands-on review on 2026-10-22 found the app mixes the two. It uses Apple's visual idioms on top of Material components:

- inset grouped lists
- iOS Settings-style coloured icon squares
- uppercase section headers
- a "Select" text button
- blue text links

These sit next to unthemed Material dialogs and square checkboxes. The seams are what make the app feel unfinished, and copying Apple's look also makes an unofficial iCloud app look official.

## Rules

| Keep from Apple Photos | Do the Android way |
|---|---|
| Three destinations: Library, Collections, Search | A floating navigation pill as in current Google Photos, built on the Material 3 Expressive floating toolbar; system back with predictive back everywhere |
| Library grouped by day and month, newest at the top (decided 2026-10-22, as in Google Photos and Alba today) | Material top app bar that collapses on scroll; scrim behind the status bar |
| Collections sections: Pinned, Albums, Shared Albums, Media Types | Material list items and cards, `titleMedium` section headers, "See all" text buttons |
| Apple's names for the user's data: Recently Deleted, Shared Albums, Live Photos | Material dialogs, bottom sheets and menus, always in Alba's theme |
| Album pages with a cover-photo header, and shared album activity | Long-press to select; contextual top bar ("2 selected") with icon actions; check circles and inset selected tiles |
| Viewer actions: favourite, edit, share, delete; swipe up for details | Immersive black viewer with tap-to-hide chrome; details in a bottom sheet |
| Media Types list | Material search bar that expands into the search view; suggestion chips |

**Do not copy:**
- blur materials
- inset grouped lists
- text-only toolbar buttons
- iOS Settings-style coloured icon squares
- uppercase section labels
- centred iOS-style titles
- Apple's typography

**Leave out Apple features with no Android meaning** (Memories as Apple does them) until there is a native design for them.

## Foundations

- **Colour:** blue is the primary colour for buttons, links and selection, generated as a Material light and dark tonal palette from Alba's current blue. There are no accent colours for now: no secondary or tertiary hues beyond what the blue palette provides, and the logo gradient stays in the logo. Dynamic colour is offered as a setting under Appearance, off by default (all decided 2026-10-22).
- **Type:** the Material 3 type scale on the system font. If the brand needs a display face, use it only for onboarding and empty-state headlines.
- **Shape:** the Material 3 shape scale.
  - Photo tiles are square-cornered in the grid and inset with rounded corners when selected.
  - Sheets and cards use the theme shapes.
- **Components:** Material 3 1.4 (Compose BOM 2026.09).
  - **Stable now:** `TopAppBar` / `LargeTopAppBar`, `ModalBottomSheet`, `AlertDialog`, `ListItem`, `SearchBar`, `FilterChip`.
  - **Expressive components** (`HorizontalFloatingToolbar`, button groups) are still experimental. Use the floating toolbar for the main navigation pill (decided 2026-10-22, matching current Google Photos), and for the viewer and editor toolbars.
  - **The navigation pill:**
    - holds Library and Collections, with Search as its trailing action
    - hides on scroll down and returns on scroll up
    - sits above the gesture area
    - is covered by bottom content padding, so the last row and the grid footer are never behind it
  - The design-system wrappers (`AlbaCard`, `AlbaListRow`, `AlbaSheetBody`) become thin themed wrappers around these, or are removed.
- **Icons:** Material Symbols (outlined, filled when active), one tint rule per surface. The favourite heart is white on photos and filled, in the surrounding icon colour, in the viewer and lists.
- **Motion:** Material motion tokens, shared-element transitions from grid to viewer (kept), and predictive back animations.

## Screens

1. **Onboarding:** one screen that leads with photos rather than feature cards.
   - **Hero:** a collage of the demo pictures, one headline and one sentence.
   - **Actions:** "Connect iCloud" as the primary action and "Try the demo" as the secondary.
   - **Privacy and backup:** the password and backup explanations move to the sign-in and backup-setup screens, where they apply.
   - **Back from sign-in:** returns here. "Try the demo" must also stay reachable from the signed-out empty state.
2. **Sign-in and two-factor:**
   - a Material top app bar
   - outlined text fields
   - the primary button pinned above the keyboard
   - the privacy note directly under the password field
   - "Compatibility details" as an expandable list item
3. **Library:**
   - **Top bar:** a collapsing top app bar titled "Library", with the account avatar and an overflow menu (filter and sort, grid size).
   - **Grid:** edge to edge, with a status-bar scrim and the fast-scroll date label.
   - **Phone-photos prompt:** a slim, dismissible banner instead of the large card.
   - **Shared albums:** move to Collections, as in Apple Photos.
   - **Empty states:** they explain the actual state (signed out, no photos, filter active) and offer the matching action.
   - **Upload:** an extended floating action button ("Upload") replaces the top-bar "+".
4. **Selection:**
   - long-press to start; drag-select is kept
   - a contextual top app bar with a close button, "N selected", and share, add-to-album and delete icons plus an overflow menu
   - check circles on every tile, a circle per day header, and inset selected tiles
5. **Viewer:**
   - **Layout:** a black background in both themes, and a tap toggles the chrome.
   - **Top bar:** back, date and time, overflow.
   - **Bottom bar:** share, edit, favourite, info and delete as icon buttons.
   - **Details:** a bottom sheet with plain-language storage state ("Available offline" rather than "Preview cached").
6. **Editor:**
   - a bottom toolbar of icon buttons: rotate, flip, aspect ratio
   - aspect-ratio chips: Free, Original, Square, 4:3, 16:9
   - "Save copy" as a filled button
   - the explanatory paragraph becomes a one-time hint
7. **Collections:**
   - **Pinned:** Favorites, Recently Deleted and Offline.
   - **Albums and Shared Albums:** horizontal rows with "See all".
   - **Media Types:** empty types are hidden.
   - **Phone photos:** one "On this phone" entry replaces the current tile and row.
   - **Trash:** phone trash and iCloud Recently Deleted appear under one "Recently Deleted" entry with two clearly labelled sources (decided 2026-10-22).
8. **Album and shared album:**
   - **Header:** a large collapsing top bar over the cover photo; actions are top-bar icons plus an overflow menu, with no "Select" text button.
   - **Grid:** the same density as the library.
   - **Adding photos:** a floating action button for albums the user can contribute to.
   - **Activity:** shared album activity is a tab.
9. **Search:**
   - a Material search bar
   - honest placeholder text ("Search by file name")
   - suggestion chips for Favorites, Videos, Live Photos, Screenshots and a date range
   - recent searches
10. **Account and settings:** a Material settings screen that replaces the gradient profile card.
    - **Header:** account header with avatar, Apple Account name and iCloud storage bar.
    - **Sections:** Backup, Storage on this phone, Appearance, About.
    - **Connection diagnostics:** move under About → Troubleshooting.
11. **Uploads and activity:**
    - **Entry point:** an upload-status icon with a progress ring or badge replaces the bell.
    - **Sheet:** it opens a transfer list.
    - **Upload:** there is a single upload entry point, the Library floating action button.

## Delivery

Each step is its own PR. Every PR includes:
- before and after screenshots in light and dark
- updated device tests, with test tags preserved where the element survives
- passing CI

Store screenshots are regenerated at the end.

1. **Foundations:** the blue colour scheme, type, shapes, themed dialogs and sheets, and icon and heart rules. No layout changes.
2. **Shell:** the floating navigation pill, top app bars, status-bar scrim, selection mode, the upload floating action button and the upload-status entry point.
3. **Onboarding, sign-in and empty states.**
4. **Viewer and editor.**
5. **Collections and album pages.**
6. **Search, account and settings.**
7. **Store screenshots and README screenshots.**
