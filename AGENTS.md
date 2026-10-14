# Project Overview
Home Location is an Android app that:
- tracks device movement (activity recognition, Wi-Fi changes, travel distance)
- publishes GPS coordinates to PubNub for a Home Assistant device tracker
- battery-conscious: balanced tracking while stationary, high accuracy while moving

# Build Commands
- `./gradlew assembleDebug` - Build the debug version of the app.
- `./gradlew testDebugUnitTest` - Run unit tests.

# Code Style Guidelines
- Use camelCase for variable names.
- Follow the Android coding conventions.
- Keep the methods concise
- Focus on single responsibility of a method and class
- Avoid adding unnecessary comments
- Make sure you optimize the imports

# Feature work
- Always create a new branch for new features
- Keep the commit message clean without mentioning the files
- Focus on what the actual commit added/removed
- Depending on the type of work, prefix commit with: 'feat' (for feature), 'fix' (for fixes), 'other' for other type of work

# Error handling and logging
- Handle and test error handling in case of connection errors
- Use standard logging mechanisms to log errors
- Avoid logging sensitive data

# Testing rules
- Create unit tests where possible
