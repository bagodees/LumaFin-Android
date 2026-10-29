# Instructions for AI coding assistants

This repository is LumaFin for Android phones, foldables and tablets. The TV app is a separate repository,
[LumaFin-AndroidTV](https://github.com/bagodees/LumaFin-AndroidTV) (a fork of the official Jellyfin Android TV
app). These rules apply to every assistant working here (Claude, ChatGPT/Codex and others).

## Follow the roadmap in order

- Read [ROADMAP.md](ROADMAP.md) before starting feature work. It names the current version.
- Only work on items in the current version unless the owner approves an exception in the conversation.
- New ideas go to a later version; don't add them to the version in progress.

## Shared code

- `core/` and `design/` are copies of the modules with the same names in LumaFin-AndroidTV. A change to
  either belongs in both repositories.
- The app ID is shared with the TV app (`io.github.bagodees.lumafin`) and must never change. Phone version
  codes start at 1,000,000,000.

## Before finishing

- Keep ROADMAP.md in sync with what was done.
- Don't publish releases, store submissions, posts or other public changes without the owner's explicit approval.
