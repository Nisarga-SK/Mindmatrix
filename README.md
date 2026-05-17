# Namma-Haadi

Namma-Haadi is a Kotlin-based Android application that helps villagers map rural shortcut paths and helps travellers avoid unsafe routes. The app uses Google Maps, GPS, Firebase Authentication, and Firebase Realtime Database to provide real-time community-based shortcut and safety updates.

## Features

- Gmail login using Firebase Authentication
- Role-based access for Villager and Traveller
- Villagers can draw shortcut paths on Google Maps
- Villagers can add, edit, and delete shortcut paths
- Path conditions: Dry, Muddy, and Flooded
- Travellers can view shortcut paths on the map
- Real-time safety updates using Firebase Realtime Database
- Flood alerts for unsafe paths
- Voice-over alerts using Android Text-to-Speech
- Center on current location using GPS
- Nearby danger checking
- Share nearby safety alerts through other apps
- Offline cached shortcut data using SharedPreferences
- Community reports and leaderboard support

## Tech Stack

- Kotlin
- Android Studio
- Google Maps API
- Firebase Authentication
- Firebase Realtime Database
- Google Play Services Location API
- Android Text-to-Speech
- SharedPreferences

## User Roles

### Villager

Villagers can:
- Login using Gmail
- Draw shortcut paths on the map
- Save shortcut name and condition
- Edit or delete their own shortcuts
- Update path conditions
- Contribute to the community map

### Traveller

Travellers can:
- Login using Gmail
- View available shortcut paths
- Filter paths by condition
- Receive flood alerts
- Use voice alerts
- Check nearby dangerous paths
- Share safety alerts with others

## Firebase Setup

Create a Firebase project and enable:

- Firebase Authentication
- Google Sign-In provider
- Firebase Realtime Database

Add an Android app in Firebase using your app package name.

Download the `google-services.json` file and place it inside:

```text
app/google-services.json
Google Maps API Setup
Enable Google Maps SDK for Android in Google Cloud Console.

Add your Maps API key in:

secrets.properties
Example:

MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
For security, restrict the API key using your Android package name and SHA-1 certificate fingerprint.

Firebase Database Structure
Example structure:

{
  "users": {
    "userId": {
      "uid": "userId",
      "name": "User Name",
      "email": "user@gmail.com",
      "role": "villager"
    }
  },
  "shortcuts": {
    "shortcutId": {
      "id": "shortcutId",
      "name": "School Shortcut",
      "condition": "Dry",
      "contributorName": "User Name",
      "contributorUid": "userId",
      "distanceMeters": 350,
      "dryReports": 1,
      "muddyReports": 0,
      "floodedReports": 0,
      "points": [
        {
          "lat": 12.9716,
          "lng": 77.5946
        }
      ]
    }
  },
  "shortcutReports": {
    "shortcutId": {
      "userId": {
        "condition": "Flooded",
        "reporterUid": "userId",
        "reporterName": "User Name"
      }
    }
  }
}
**How to Run**
Clone the repository.
git clone https://github.com/your-username/namma-haadi.git
Open the project in Android Studio.

Add google-services.json inside the app folder.

Add secrets.properties in the project root with your Google Maps API key.

Sync Gradle.

Connect an Android device or start an emulator.

Run the app.

Permissions Used
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
**Project Goal**
The goal of Namma-Haadi is to improve last-mile rural connectivity by allowing villagers to become local map contributors and helping travellers avoid muddy, flooded, or unsafe shortcut paths.

**Future Enhancements**
Firebase Cloud Messaging push notifications
Photo proof for flooded or muddy paths
Kannada language support
Admin verification system
Advanced route navigation
Offline-first map storage
Safety score for each shortcut
Public release with signed APK
**License**
This project is created for academic and internship learning purposes.
