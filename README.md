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

The tasks to prepare are:
 - Globally:
   - Prepare the challenges in the [Google Sheet configuration](#google-sheet-configuration).
 - Per machine:
   - Install the challenge IDE plugin
   - Configure the Google Sheet settings
   - Configure the team color

### Google Sheet Configuration

The Google Sheet exists of 3 columns:
Challenge **end** time, Challenge duration and Challenge image URL.
The end time is parsed as hh:mm, duration is parsed as hh:mm:ss.
The image url must be a link to a raw image. The following is an example csv:
```csv
Challenge end time,Challenge duration,Challenge image URL
14:13,12:30:00,https://github.com/someUser/someRepo/blob/some/path/some-image.png?raw=true
14:43,12:30:00,https://github.com/someUser/someRepo/blob/some/path/next-image.png?raw=true
```
This example lists 2 challenges. One starts at 15:00:30 and runs for 13.5 minutes.
The second starts at 15:30:30 and runs for 13.5 minutes. Both with different images.

Make sure the Google Sheet is shared with viewer rights publicly by link.

Once that is set up, you can go to the IDE that has this plugin installed,
then in the settings, configure the Google Sheet ID to point to the sheet containing the configuration.
Then invoke the "Refetch Challenges" action.

### Install the plugin.

Before you [install the plugin](#install-the-plugin-from-disk), you might first need to [build it](#build-the-plugin).

#### Build the plugin.

This step creates the plugin jar, and it only has to be done once.
Then you can simply distribute the jar to the challenge machines

Run the following task:
```sh
./gradlew buildPlugin
```
You will then find it in the [build/distributions](build/distributions) directory

#### Install the plugin from disk.

Take the produced jar, and install it.
 - Goto: [settings > plugin](jetbrains://idea/settings?name=Plugins)
 - Install the jar via the "[install from disk](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)" function.

### Set up the challenge machines

Now checkout [the challenge repo](https://github.com/brokenhappy/KotlinConfChallenge25)
and follow the [setup instructions](https://github.com/brokenhappy/KotlinConfChallenge25?tab=readme-ov-file#setup) there.