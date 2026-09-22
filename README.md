# JARVIS Android — Groq + Fish Audio

Core personal assistant build for Android phones.

## What it does
- Typed chat with Groq
- Speech input using Android SpeechRecognizer
- Fish Audio text-to-speech
- Local settings for Groq API key, Fish API key and Fish voice/reference ID
- JARVIS-style dark/cyan interface

## APIs
- Groq: https://api.groq.com/openai/v1/chat/completions
- Fish Audio: https://api.fish.audio/v1/tts

## Build
Open the folder in Code on the Go, allow the project to sync, then build/install the app.

For the current CoGo toolchain, this project uses AGP 8.11.0 and Kotlin 1.9.2.

## Important
API keys are personal secrets. Do not paste them into public GitHub repositories or screenshots. This first personal build stores them locally in SharedPreferences; a production app should use a backend so keys are not exposed in the client.
