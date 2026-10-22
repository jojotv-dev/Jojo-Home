# System Continue Watching Design

## Goal

Show the resume items that Android TV applications deliberately publish to third-party launchers, including Netflix, SmartTube, and JojoTV.

## Data sources

Jojo Home will read `TvContract.WatchNextPrograms` only after Android grants `android.permission.READ_TV_LISTINGS`. This is the same Android TV system surface exercised by Monet. The existing JojoTV bridge remains the fallback for JojoTV, and the existing preview-channel rows remain recommendations rather than resumes.

## User experience

Jojo Home requests the permission once on first Home launch when it has not yet been granted. If it is denied, the Home remains functional and simply omits system resumes. A successful grant is persistent until Android revokes it, app data is cleared, or the app is reinstalled.

## Rendering and launch

The system-resume row uses the published title, episode description, poster/thumbnail, and `intent_uri`; activating a card resumes through that intent. Items with no usable intent are not presented. A JojoTV bridge item is used only when the system contract does not already provide a JojoTV resume.

## Scope limits

Only items exposed through Android's public `TvContract` are shown. Proprietary in-app histories remain inaccessible to third-party launchers.
