# Blocktime privacy policy

**Effective date:** 29 September 2026

Blocktime is a focus timer disguised as a flight. This policy explains what happens to your data
when you use the Android app. The short version: **Blocktime has no accounts, no analytics, no
ads and no crash reporting. The developer does not collect, receive or store any of your data.**
The one exception is optional and off by default: if you turn on shared challenges, the room
server described below receives your pilot name, pilot code and your progress on the challenges
you share, and nothing else.

## Data stored on your device

Blocktime keeps the following only on your phone, in the app's private storage:

- your pilot profile: the username you choose, a randomly generated pilot code and your home
  airport
- your logbook: the flights you have flown, with their routes, durations and dates
- your challenges, achievements and progress
- your settings, such as theme, map style, engine sound and the offline maps switch

None of this is sent to the developer or to anyone else. It is deleted when you uninstall the
app or clear its data in Android's settings.

If Android backup is turned on for your device, Android may copy this data into your own Google
account backup, or move it to a new phone during device transfer, so your logbook survives a
phone change. This is handled by Google under your Google account's settings; the developer has
no access to it. The bundled airport database and cached map images are not included.

## Services the app connects to

To draw the globe and the arrival screen, the app loads content directly from the third-party
services below. Like any website you visit, each service receives your device's IP address and
the standard information of a web request (for example, the app's user agent) along with what is
requested. The developer does not receive any of it.

| Service | What the app requests | Privacy policy |
|---|---|---|
| CARTO | Map tiles for the Standard map style (the part of the world on screen) | [carto.com/privacy](https://carto.com/privacy) |
| Esri (ArcGIS) | Satellite imagery tiles for the Satellite map style | [esri.com/privacy](https://www.esri.com/en-us/privacy/overview) |
| Amazon Web Services (Terrain Tiles) | Elevation tiles for the Satellite map style's terrain | [aws.amazon.com/privacy](https://aws.amazon.com/privacy/) |
| Pexels | A photo of your destination city for the arrival screen. The search sends the city and country name, then downloads the chosen photo | [pexels.com/privacy-policy](https://www.pexels.com/privacy-policy/) |
| Room server (shared challenges, only when switched on) | Your pilot name, pilot code and progress on the challenges you share or join; see the section below. The operator and address of the server are named in the app's release notes for the build you have installed | named in the release notes |

No username, pilot code, logbook or other personal information is included in the requests to the
first four services. The room server is the exception, and it only ever receives anything if you
switch shared challenges on.

If you turn on **Offline maps** in Settings, or your phone has no internet connection, the app
uses its built-in offline map and does not contact any of these services.

## Shared challenges (optional)

Shared challenges let you race a friend to a destination, or fill a distance, set or streak goal
together, through a six-character room code. **The feature is off by default.** With it off, the
app contacts no room server, none of the sharing controls exist, and everything in this policy is
as described above. You can turn it on and off at any time under Settings, and the app works
fully without it.

When it is on, and only for the rooms you create or join, the app sends the room server:

- your pilot name and your pilot code, so your crew can see who you are
- the challenge's definition (its type, name and target), so your crew can join the same goal
- your progress numbers on that challenge: for a race your position and how far you have got, for
  a set the members you have reached, for a distance goal the kilometres you have flown while it
  ran, for a streak your day count and whether it is still alive

**The server never receives your logbook, your flight times, or anything about flights outside
the shared challenge.** There is still no account: a secret generated on your phone the first
time you switch the feature on is what lets the server tell your writes from someone else's, and
it is never shown to anyone.

**Who sees it:** the other members of the room, up to five, see your name, code and progress on
that challenge, and you see theirs. Anyone who knows the room code can look at the room and join
it while it is open, so share a code only with the people you want in your crew.

**How long it is kept:** a room is deleted from the server 30 days after the challenge ends, or
180 days after the last write to it. Leaving a room marks you as left; your contribution to a
shared goal stays in the room until the room itself is deleted, so your crew's total does not
change under them. Your own copy of the challenge stays on your phone as before.

The server's address is set when the app is built. A build without one has no sharing controls
at all and contacts no room server. The operator and address for the build you have installed
are named in its release notes; the server itself stores nothing beyond the rooms described here.

## Permissions

Blocktime only asks Android for internet access and to check whether a network connection is
available. It does not use your location, contacts, camera, microphone, photos or files.

## Children

Blocktime is not directed at children under 13 and does not knowingly collect personal
information from anyone, children included.

## Changes to this policy

If this policy changes, the updated version will be published at this address with a new
effective date. The full history of changes is visible in this file's history on GitHub.

## Contact

Questions about this policy or about your data: **2457527@gmail.com**
