# Monet-style Continue Watching Presentation

## Goal

Present public Android TV resume items in Jojo Home with the visual structure of Monet while preserving Jojo Home’s existing navigation and app grid.

## Card layout

Each resume becomes a landscape card approximately 300 dp by 170 dp. Its published thumbnail is used before its poster art because the thumbnail is the landscape asset used by Monet. The image fills the card with center-crop; a bottom-to-top dark gradient makes the title, episode description, and source application label readable. The card has rounded corners, a focused scale/border treatment, and a progress bar when position and duration are available.

## Row position setting

The default keeps the resume row below the applications grid. A new application setting, `Position des reprises`, offers `Après les applications` (default) and `Avant les applications`. Changing it rebuilds the Home immediately and persists in the existing preferences/NAS settings payload.

## Data and interaction

The cards keep using the existing `WatchNextPrograms`/Jojo bridge source and published resume intents. The layout change does not alter which applications or items are accessible.

## Validation

Unit tests cover the position setting’s value mapping and usable resume filtering. Device validation checks the landscape artwork, overlay text, focus, progress bar, and each row-position choice.
