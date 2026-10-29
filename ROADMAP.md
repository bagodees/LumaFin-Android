# LumaFin for Android phones, foldables and tablets: roadmap

The TV app (Android TV, Google TV and Fire TV) lives in its own repository,
[LumaFin-AndroidTV](https://github.com/bagodees/LumaFin-AndroidTV), with its own roadmap. This app was
started there on October 28, 2026 and moved here the same day so each app has its own code, releases and plans.

**Current version: 1.2 (alpha).** TV fixes stay first; this app runs alongside the TV versions.

## Ground rules

- It ships under the TV app's ID (`io.github.bagodees.lumafin`) as a separate phone and tablet build on the
  same store listing (decided October 28). Phone version codes start at 1,000,000,000 so they never clash
  with the TV app's; the last two digits are the alpha or beta number, and 99 for a final release.
  Never change the app ID once anything is uploaded.
- The build requires a touchscreen, so stores never offer it to a TV. On Amazon the TV build must stay
  targeted to Fire TV only once a Fire tablet build is added.
- `core/` (Seerr requests and Top Rated) and `design/` are copies of the same modules in LumaFin-AndroidTV.
  Change them in both repositories.
- Every screen follows the window's width: bottom tabs on phones and folded foldables, a side rail on
  unfolded foldables, the side menu on wide tablets.
- Test devices: the owner's Fire HD 10 (2021) and a friend's Galaxy Z Fold, plus phone, foldable, tablet
  and Fire HD emulators.

## 1.2 (alpha)

Status: In progress. Alpha 1 and alpha 2 are published as GitHub pre-releases.

1. [x] Shell: sign-in (server, users, password, Quick Connect), adaptive navigation, first Home rows. Checked October 28 on the foldable emulator (folded, unfolded, folding mid-use keeps the open tab) and a Fire HD 10 stand-in (Android 9, no Google services, portrait and landscape)
2. [x] Home and libraries: the TV app's rows, library grids (A-Z, Recently Added, Newest, Top Rated), My Requests, Trending and Upcoming; Seerr and Top Rated logic in the shared `core` module
3. [x] Details pages and the player: title pages with seasons, episodes, cast and More Like This; touch controls, audio and subtitle choices, resume and progress reporting, next episode, picture-in-picture, and a converted stream when a device can't decode a file
4. [x] Search (with Seerr titles not in the library), Seerr title pages and requests, settings (autoplay, Seerr rows)
5. [x] Foldable and tablet polish: info beside the episode list on wide screens, tabletop video mode on a half-folded foldable
6. [x] Downloads and casting, picked by the owner October 28: downloads in original quality or a smaller converted copy (1080p, 720p, 480p), single titles or a whole season, a Downloads tab, offline playback with resume points sent to the server once back online, and Wi-Fi only by default. "Play on" sends a title to a Chromecast (Jellyfin's Cast receiver; needs Google Play services, so not on Fire tablets) or another Jellyfin app signed in to the same user, with remote controls in a bar above the tabs. Checked on the foldable emulator and on a friend's Galaxy Z Fold. Not yet checked on a real Chromecast
7. [ ] Play listing and closed testing, with the owner's approval. Release build checked October 28; listing draft in [docs/play-listing-draft.md](docs/play-listing-draft.md)
8. [x] Crash reports from GitHub test builds, approved by the owner October 29 along with LumaFin's crash server: crashes, freezes (ANR) and system kills go to the `android-phone` project on crash.getlumafin.com, with the same ntfy alert as the TV app. Server addresses, IP addresses and tokens are removed first and devices are told apart only by a random id. A crash is saved first and sent on the next start if it couldn't go right away. Only builds made with `-Plumafin.testBuild=true` (and an alpha or beta version) send anything; store builds send nothing until the privacy policy and store data-safety answers cover it. Checked on the phone emulator

### Fixed

- [x] Play on listed other users' devices for server admins; it now lists only the signed-in user's own devices (October 28)

## Later

- iPhone, iPad and Apple TV apps will get their own repository.
