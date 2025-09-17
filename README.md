# KotlinConfChallengePlugin

This plugin adds a couple of features to IntelliJ to help with the KotlinConf challenge.

## Features
 - **Auto-save.** This plugin makes IntelliJ save on each keystroke, so that Compose Hot Reload (CHR) can pick the changes up.
   This is done this way, since the user won't see the CHR session.
 - **Challenge tool window.** This tool window shows:
   - The countdown until the challenge starts.
   - The button to reveal the image once the challenge has started.
   - Halfway through, it shows a button that allows the user to run the application a single time.
     (It does this by text search for a run config with "main \[jvm]")
 - **Refetch Challenges Action.** Press `Ctrl + opt + cmd + R` to update the local cache file from the configuration in the Google sheet. 
 - **Create Patch and Undo Changes Action.** This stores the user's changes and then rolls them back.
   The reason we create a patch is that people might come back later and ask for their changes.
   The changes will get stored next to the local cache file named after their date and time.
 - **Settings.** With the settings you can configure:
   - The ID for the Google sheet.
   - The location of the local cache file. This has the same default as the challenge runtime. Only change if you change it there too.
   - Whether to automatically start the run configurations when the challenge starts.
   - Whether to automatically start a cancellable 20-second countdown when the challenge is over to invoke the "Create Patch and Undo Changes" action.
     (Can be canceled by cancelling the progress bar that you will see in the bottom right corner)

## Setup

In the settings, configure the Google Sheet ID to point to the sheet containing the configuration.
Then invoke the "Refetch Challenges" action.
