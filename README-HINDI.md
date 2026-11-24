# MD Reel Current Affair — Scroll Stopper v18 build

## Included files
- `index.html`: latest Hybrid reel-maker interface with offline-only native Hindi TTS for video export.
- `NativeTtsPlugin.java`: Android TTS plugin that refuses network-only Hindi voices and selects an installed local Hindi voice.
- `VideoFixerPlugin.java`: supplied MP4 remux/gallery saver.
- `MainActivity.java`: registers both native plugins.
- `package.json`, `capacitor.config.json`, `manifest.webmanifest`: supplied app configuration.
- `.github/workflows/build-android.yml`: supplied GitHub Actions workflow.

## GitHub upload
अपनी नई repository के root में इस ZIP के अंदर मौजूद `MD_Reel_Current_Affair_Complete` फोल्डर की सभी फाइलें अपलोड करें। `.github/workflows/build-android.yml` का पूरा path और नाम जस का तस रखें। HTML फाइल repository root में `index.html` नाम से होनी चाहिए। Workflow पहले `npm run build` से `www/` बनाता है, फिर दोनों Java plugins और custom `MainActivity.java` को Android source tree में कॉपी करता है, Manifest में TTS/audio permissions जोड़ता है और उसके बाद APK बनाता है।

## Before testing offline voice
On the Android phone, open **Settings → Text-to-Speech output** (wording varies by phone) → preferred engine / language / voice data, and download Hindi voice data for the installed TTS engine. The app now deliberately does not call online TTS services during video export.

Then run the GitHub Actions workflow and install the newly generated APK. Test with one short question first. Preview playback may use Android TTS; exported-video speech is synthesized to WAV, decoded through WebAudio, and routed into the recorder audio track.

## Important validation note
The source has been statically checked and the workflow/configuration files have been assembled. A real Android APK build and on-device audio test still need to be run in GitHub Actions and on your phone; this environment cannot certify device-specific TTS voice-data availability or MediaRecorder codec behavior.


## इस अपडेट में रील के चार आकर्षक सुधार
1. **बेहतर पढ़ने योग्य टेक्स्ट:** प्रश्न और विकल्पों के फ़ॉन्ट बड़े किए गए हैं; लंबे विकल्पों को लाइन में दिखाने की व्यवस्था जोड़ी गई है।
2. **सही उत्तर का पॉप इफेक्ट:** सही विकल्प हरे ग्लो, टिक और हल्के ज़ूम के साथ उभरता है तथा “सही उत्तर” लेबल दिखाता है।
3. **ब्रांडेड Intro/Outro:** Intro अब डिफ़ॉल्ट रूप से चालू है; शुरुआत में MD Reel Current Affair ब्रांड और अंत में Follow/Like/Share संदेश आता है। इन्हें ऐप की सेटिंग से बदला जा सकता है।
4. **स्मूद ट्रांज़िशन:** Intro, हर प्रश्न और Outro के बीच हल्का fade-in ट्रांज़िशन जोड़ा गया है।

ये बदलाव मुख्यतः एक्सपोर्ट होने वाली 9:16/16:9 वीडियो की Canvas rendering में किए गए हैं। वास्तविक APK/डिवाइस पर दृश्य और ऑडियो परीक्षण GitHub Actions से बनी APK इंस्टॉल करके करना बाकी है।

## नया ऐप नाम
- ऐप का नाम: **MD Reel Current Affair**
- Android application ID: `com.md.mdreelcurrentaffair`
- यह पुराने MD Quiz Reel Maker ऐप से अलग पहचान रखेगा। नई रिपॉजिटरी में इसी पैकेज की सभी फाइलें अपलोड करें।
