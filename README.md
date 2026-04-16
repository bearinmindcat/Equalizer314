<h1><img width="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Equalizer314" align="absmiddle"> Equalizer314</h1>

<a href="https://github.com/bearinmindcat/Equalizer314/releases/latest"><img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="70"></a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/{%22id%22:%22com.bearinmind.equalizer314%22,%22url%22:%22https://github.com/bearinmindcat/Equalizer314%22,%22author%22:%22bearinmindcat%22,%22name%22:%22Equalizer314%22}"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/b1c8ac6f2ab08497189721a788a5763e28ff64cd/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="70"></a>
<a href="https://f-droid.org/packages/com.bearinmind.launcher314/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80"></a>

⚠️ PSA; shit will go wrong while using this app, there will be features missing that you wish I added; but this app is in **BETA** and in the very early stages of testing and development, so I'll try to make note of your issues, run through them myself and fix them as quick as possible. My goal is to get this app to a clean smooth state, but in order to do that, people like you can help test and present issues & features that come up. I will do my best to fix everything in a timely manner while incorperating **YOUR** feature wishlist in a way that I think will work for everyone :)

⚠️ Another PSA; these stores below are in the pipeline. As my apps get approved on each of them I'll move them up to the section right underneath the title section of the readme. As of now these links take you nowhere. With that being said, you may wait for the app to be uploaded to a trusted store, and I'll continue developing and making changes for those of you who download it the "raw" way. Thanks again.

<a href="https://accrescent.app/app/com.bearinmind.launcher314"><img alt="Get it on Accrescent" src="https://accrescent.app/badges/get-it-on.png" height="60"></a> <a href="https://play.google.com/store/apps/details?id=com.bearinmind.launcher314"><img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60"></a> <a href="https://apt.izzysoft.de/packages/com.bearinmind.launcher314"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="60"></a>

## About

To start off there is really no "free" and/or "open source" alternatives to Wavelet and p\Poweramp EQ out there, and I felt like after using both of those apps ammong other various EQ apps that there were huge shortcomings in terms of the features & accessibilities they offered. When I started developing this app I wanted to have both a powerful parametric eq function with minimal permissions; this is why I choose to use both the DynamicsProcessing & Visualizer APIs as the framework for ths app as you only need minimal permissions for both of them to work in tandem. There are shortcomings from both these APIs, but I'll discuss more of that later.

To start off this app is built off of DynamicsProcessing API you can read more about the documentation and features it offers here (https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing); this is the same API that Poweramp EQ & Wavelet both use but I felt in some aspects they wern't really being able really squeeze out the max potential that this API can offer.

As someone who comes from the audio production & IEM world I have a lot of experience understaning what both sides of those worlds want and wanted to be able to take advantage of both to create something that met my creative endeavors.

Using the Visualizer API I wanted to give users a way they can reliably cross reference visual data with audio data; this is both useful for the main EQ portion as you can visually reference audio changes you make, but this comes more into play with the Limiting & Multiband Compression sides of the app. While these features are for use in the DynamicsProcessing API; and many apps like Poweramp EQ & Wavelet (who premium locks these features); they just add knobs/sliders in and expect the user to understand exactly how these features work and without real audio-visual feedback, users aren't really able to take advantage of these features in their full potential. This is the reason why many DAWs and VST Plugins use audio-visual feedback with these functions specifically.

Using Visualizer API with these DynamicsProcessing features; you're really able to get an intimiate audio-visual feedback loop that gives you complete control over your audio framework. Shown below are some screenshots of the Multiband Compression & Limiter and how the Visualizer API functions with the input curves, gr trace curves, & the limiter waveform metering. 

<img width="522" height="383" alt="Screenshot (1534)" src="https://github.com/user-attachments/assets/2449b96f-8306-4319-9eb0-ddead8ea84e5" />
<img width="518" height="378" alt="Screenshot (1535)" src="https://github.com/user-attachments/assets/720725c4-6205-456c-ad83-c2667757927d" />

A lot of other funtions that don't use the Visualizer API I also wanted to still give correct visual feedback (same style implementation that many DAWs use) such other various functions like the compressor & attack/release visuals. You can independently change these values with the slider and by moving you finger along the line/graph itself. This occurs in other places in the app as well, but these are two good examples of this.

<img width="525" height="389" alt="Screenshot (1538)" src="https://github.com/user-attachments/assets/c8923af2-b592-4476-aea9-f07be44b9ba2" />
<img width="526" height="588" alt="Screenshot (1537)" src="https://github.com/user-attachments/assets/261c4359-4f0d-44af-a199-068f570ff3ea" />

## Why DynamicsProcessing & Visualizer APIs?

There exists other apps and methods for device eq & visualization; but I wanted to talk about why I choose DynamicsProcessing & Visualizer as the framework for this app vs the other available and why I choose not to use those. To touch on this point again I choose to use the DynamicsProcessing API, the same API that both popular apps such as Poweramp EQ and Wavelet use as I decided DynamicsProcessing had enough tangibility in coparison to what I had to sacrifice using other more powerful methods.

Other methods of EQ available (ranked from);

Androids built-in Equalizer class (android.media.audiofx.Equalizer)
- Fixed amount of EQ bands, this is what many "lazy" eq apps use and other apps who want to use EQ but don't want to focus on building an EQ engine (audiobook apps, music players, media players, video players, etc)
- Attaches to an audio session

AudioEffect API subclasses (https://developer.android.com/reference/android/media/audiofx/AudioEffect)
- Much better than androids built-in equalizer class but still lacks in comparison to DynamicsProcessing
- A lot of apps build with DynamicsProcessing & AudioEffects as there are some subclasses available within the API that can offer "different" features that DynamicsProcessing cannot and they can attach to the same audio session pipeline
- Attaches to an audio session

AudioPlaybackCapture (RootlessJamesDSP)
- Has much more access to the audio framework than what any of the avaialble APIs above can do, but in order to do so you need to grant ADB perms using something like Shizuku, going this way would also provide a much more accurate Visualizer/Spectrum; but going this route would force you to use the RECORD_AUDIO permission while also increasing latency with audio, and I wanted to keep the permissions in my app as low as possible and there was already a well developed app using this method so I wanted to stay clear of doing something that was already done
- Another limitation is that some apps such as spotify block internal audio capture

AudioFlinger (JamesDSP & ViPER4Android)
- This is the "best" method if you really want control over your audio without latency issues. There is no "down-side" to using this method other than you need a rooted device which steers a lot of people away. This along with RootlessJamesDSP are best used if you want to apply custom audio effects directly without relying on android's built-in effects.
- only con? root.

## Presets & EQ Generation & AutoEQ

To reference, lots of apps run AutoEQ (https://github.com/jaakkopasanen/AutoEq/wiki/Choosing-an-Equalizer-App); including Wavelet & Poweramp EQ. What both those apps don't offer are either "free access to auto eq" (comon wavelet....) & using the built in AutoEQ algorithm from the AutoEQ Github which you can take a look at here (https://github.com/jaakkopasanen/AutoEq/wiki/How-Does-AutoEq-Work%3F). This algorithm is done on the "Generate Custom EQ" section of the app; you need a "measurement" & "target" which both can be taken from squig.link along various resources online.

On top of this I would also like to mention maintaining homogenity between preset sharing among popular equalization "applications"; this why I want with APO as the main export method than having an independent export method such as what Poweramp EQ & Wavelet use (Also as shown below in the "Generated EQ" portion). This would allow you to transfer the exported APO file to your desktop equalization software (EqualizerAPO) without having running into conversion issues. I was thinking about creating a conversion software in the app or on this github so incase people want to trasnfer over from Wavelet & Poweramp EQ, they can with ease. It might be something I will implement later if a lot of people request.

<img width="538" height="886" alt="Screenshot (1539)" src="https://github.com/user-attachments/assets/721ffdb1-195a-4b50-b53e-28221787793d" />

## Acknowledgements

  - AutoEq — Jaakko Pasanen — https://github.com/jaakkopasanen/AutoEq
  Cited in autoeq/EqFitter.kt — your target-curve fitting algorithm is based on theirs.
  - audio-analyzer-for-android — bewantbe — https://github.com/bewantbe/audio-analyzer-for-android
  Cited in dsp/FFT.kt and dsp/SpectrumAnalyzer.kt — Apache 2.0. Your Cooley-Tukey FFT and windowing came from here.
  - pyloudnorm — Christian Steinmetz — https://github.com/csteinmetz1/pyloudnorm
  Cited in audio/LufsProcessor.kt as the Python reference implementation of EBU R128.
  - libebur128 — jiixyj — https://github.com/jiixyj/libebur128
  Cited in audio/LufsProcessor.kt as the C reference implementation of EBU R128.

  Algorithmic / academic references (not GitHub — listed here for completeness)

  - Audio EQ Cookbook — Robert Bristow-Johnson — https://www.w3.org/TR/audio-eq-cookbook/
  The RBJ biquad formulas in dsp/BiquadFilter.kt and autoeq/EqFitter.kt.
  - Matched Second Order Digital Filters — Martin Vicanek — https://www.vicanek.de/articles/BiquadFits.pdf
  The useVicanekMethod path for Bell filters.
  - Giannoulis / Massberg / Reiss 2012 — Digital Dynamic Range Compressor Design (JAES) —
  https://www.eecs.qmul.ac.uk/~josh/documents/2012/GiannoulisMassbergReiss-dynamicrangecompression-JAES2012.pdf
  Soft-knee transfer function in CompressorCurveView, MbcGainComputer, and EqGraphView.
  - Linkwitz-Riley crossovers — https://en.wikipedia.org/wiki/Linkwitz%E2%80%93Riley_filter
  The 4th-order crossover math in EqGraphView.kt for MBC.
  - ITU-R BS.1770 — https://www.itu.int/rec/R-REC-BS.1770
  The LUFS loudness standard that LufsProcessor.kt follows.
  - JUCE Compressor — https://github.com/juce-framework/JUCE (class reference: juce::dsp::Compressor)
  Cited in MbcGainComputer.kt as a reference implementation of the EMA envelope follower pattern — not code copied, just the standard approach cross-checked against JUCE.

## Known Issues

### Conflicts with other audio effect apps

Equalizer314 uses Android's DynamicsProcessing API on audio session 0 for system-wide audio processing. Only one app can control session 0 at a time. If another equalizer or audio effect app is installed (such as Precise Volume, Wavelet, Sound Assistant, or any other EQ app), they will fight over session 0 and cause audio glitches or the EQ to turn off unexpectedly.

**If you experience the EQ turning off on its own, uninstall or disable other audio effect apps and reboot your device.**

Equalizer314 includes an auto-reclaim feature that will attempt to reclaim session 0 if another app takes it over, but this cannot fully prevent brief audio dropouts when two apps are competing.

This is a limitation of the Android audio framework, not specific to Equalizer314. Other apps with the same limitation:

- **Wavelet** — uses DynamicsProcessing on session 0. Their FAQ states: *"Wavelet will often not function as expected if other equalizer/hearing aid applications are installed. Freezing or uninstalling the offending application + rebooting your device will resolve issues caused by this."*
- **RootlessJamesDSP** — uses internal audio capture instead of DynamicsProcessing, but still conflicts. Their README states: *"Cannot coexist with (some) other audio effect apps (e.g., Wavelet and other apps that make use of the DynamicsProcessing Android API)."* Because RootlessJamesDSP captures audio after DynamicsProcessing has already modified it, then outputs processed audio back through session 0, the result is double processing.
- **Poweramp EQ** — uses DynamicsProcessing on session 0 for system-wide mode.
- **Precise Volume** — uses DynamicsProcessing on session 0 for volume processing. Recreates its instance on every volume change, which disconnects other apps.

### Apps that bypass the system audio pipeline

Apps using AAudio / OpenSL ES low-latency paths, MMAP / offload modes, or telephony audio will not be affected by Equalizer314 (or by any session-0 EQ). This includes most low-latency music games and the in-call audio path.

## Contributing

Issues and PRs welcome. Some ground rules:

- File a bug report with: device model, Android version, the EQ mode you're in, and (if relevant) a list of other audio apps installed.
- Feature requests are fine — open an issue first so we can talk through fit before you write the code.
- For PRs, keep the diff focused on one concern; don't bundle unrelated cleanup with feature work.

## Acknowledgment/Resources

All of these acknowledgements are mentioned in code comments, but I wanted too also include them here as well, because I think these are all good resources to read up on.

- [Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/) - biquad math for the parametric eq.
- [Matched Second Order Digital Filters](https://www.vicanek.de/articles/BiquadFits.pdf) — bell filter math for parametric eq.
- [AutoEq](https://github.com/jaakkopasanen/AutoEq) - used for target curve/measurement fitting + autoeq presets.
- [*Digital Dynamic Range Compressor
  Design*](https://www.eecs.qmul.ac.uk/~josh/documents/2012/GiannoulisMassbergReiss-dynamicrangecompression-JAES2012.pdf) - used for hard/soft knee transfer function for the multiband compression
- [ITU-R BS.1770](https://www.itu.int/rec/R-REC-BS.1770) - used LUFS measurements
- [Linkwitz–Riley crossover](https://en.wikipedia.org/wiki/Linkwitz%E2%80%93Riley_filter) - used for crossover math in multiband compression section

## License

Equalizer314 is released under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full text.

You are free to use, modify, and redistribute this software under the terms of the GPL v3. If you distribute a modified version, you must release the source under the same license.
