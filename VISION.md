# Alba

Alba is a free, open-source Android photo app for teens aged 13 and older and adults who want to use their existing iCloud library and Shared Albums.

## Principles

- Connect directly to Apple; keep credentials and photo data off Alba-operated servers.
- Make browsing, playback and everyday photo tasks feel natural on Android.
- Be iCloud Photos for Android: native Android in structure and behaviour, Apple and iCloud in look and feel. Stock Material 3 components, system back, adaptive layouts and Google Photos' gestures and small details, dressed in an iCloud identity (colour, large titles, translucent materials, spring motion, Apple's names for iCloud data) that sets Alba apart from Google's apps. See [docs/design.md](docs/design.md).
- Make backup and deletion explicit, verifiable and under the user's control.
- Be clear about unofficial Apple integration and about simulated demo behavior.

## Current priorities

- Complete Play Store setup and make review and testing straightforward.
- Make backup work like Google Photos: every photo and video, existing ones included, reaches iCloud reliably with its original file and correct date.
- Keep deleting phone copies (Free up space) out of release builds until backup is proven on real accounts.
- Start release builds at iCloud sign-in. The demo library is a development tool and stays out of release builds.
- Keep photo browsing, sync and account handling reliable.
- Rebuild the theme and components to that direction: a complete iCloud colour scheme, the Material type scale, grouped lists, large titles, one icon set and tablet layouts.

## Not being built

- A child-directed service or an under-13 sharing experience.
- A replacement photo-hosting service.
- Support for iCloud Shared Photo Library; Shared Albums are supported separately.
