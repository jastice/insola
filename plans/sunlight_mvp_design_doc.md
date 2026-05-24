# Sunlight MVP Design Doc

## Summary

Build an Android-first app that estimates daily UV exposure and vitamin D opportunity from live UV data, location, and lightweight phone signals. The app should behave like a passive sunlight budget tracker: it integrates UV over time, estimates accumulated dose, forecasts time-to-threshold from the future UV curve, and lets the user correct the model with simple manual actions such as “indoors,” “shade,” and “sunscreen.”

The MVP is not a medical dosimeter and does not attempt precise dermatological advice. It is a casual, low-friction estimator for people who want a better sense of their real sun exposure across the day.

Android is the first target. The core model should be designed as Kotlin Multiplatform-compatible business logic, with Android-specific sensing and background execution behind interfaces.

## Product Shape

The app’s central concept is a daily sunlight budget.

Users should be able to answer three questions quickly:

1. How much UV exposure have I accumulated today?
2. How much useful vitamin D opportunity have I probably had?
3. If I stay outside, roughly how long until I reach my configured burn-risk threshold?

The key differentiator is time integration. The app should not assume the current UV index remains constant. It should integrate the expected UV curve over the day and over future exposure intervals.

For example, two hours outdoors at UV 3 in the morning may produce a very different dose from two hours outdoors at UV 3 in the evening if the UV curve is rising in one case and falling in the other.

## MVP Goals

The MVP should provide:

- Daily accumulated UV dose estimate.
- Daily vitamin D opportunity estimate.
- Time-integrated forecast: “if current exposure continues, when do I hit my burn-risk threshold?”
- Live UV index and forecast curve for the user’s current location.
- Passive exposure estimation using location, time, and simple Android signals.
- Manual correction buttons for indoor time, shade, and sunscreen.
- A simple timeline of today’s inferred exposure episodes.
- Local-first data storage.
- Shared core architecture suitable for later iOS support.

## Non-Goals for MVP

The MVP should not include:

- Dedicated wearable UV sensor support.
- Route planning.
- Social features.
- Full medical-grade photobiology modeling.
- Apple Watch, HealthKit, or iOS implementation.
- Cloud sync.
- Account system.
- Complex skin diagnostics.
- Photo-based skin tone detection.
- Continuous high-frequency sensor sampling.

## User Experience

The main screen should be a daily dashboard:

- “UV dose today”: shown as a percent of the user’s daily burn-risk threshold.
- “Vitamin D opportunity”: shown as low / moderate / likely sufficient, not as a precise blood-level prediction.
- “Current UV”: live current value.
- “Estimated time to burn”: computed by integrating the future UV curve from now onward under current assumptions.
- “Confidence”: simple low / medium / high indicator.

The app should expose simple correction controls:

- “I’m indoors”
- “I’m in shade”
- “I applied sunscreen”
- “I’m covered up”
- “Undo last correction”

Corrections should apply from now until changed, or optionally over a recently detected exposure interval. The MVP can use a simple model: a correction creates an interval event with a start time and an optional end time.

The daily timeline should show coarse inferred episodes:

- Outdoors, estimated high UV exposure
- Outdoors, low UV exposure
- Indoors / negligible UV
- Manual sunscreen interval
- Manual shade interval

This timeline is important because it makes the passive model inspectable and correctable.

## Core Model

The core model operates on time intervals. Each interval has a start time, end time, location estimate, UV estimate, exposure factor, and confidence.

The basic dose integral is:

```text
uvDose = ∫ UV(t, location) * exposureFactor(t) dt
```

For implementation, use discrete integration over forecast samples:

```text
uvDose += uvIndexSample * exposureFactor * durationHours
```

The result can be stored in UV-index-hours. Later versions may convert to Standard Erythemal Dose or Joules per square meter if desired, but MVP UX can avoid physical-unit precision.

### Exposure Factor

`exposureFactor` estimates how much of the forecast UV reaches relevant skin.

MVP factors:

```text
exposureFactor = outdoorProbability
               * shadeFactor
               * clothingFactor
               * sunscreenFactor
               * windowFactor
```

Suggested initial defaults:

- outdoors: 1.0
- probably indoors: 0.0
- uncertain: 0.3
- shade: 0.25 to 0.5
- sunscreen SPF active: configurable, conservative default such as 0.2 rather than 1/SPF
- covered clothing: 0.2
- car/window: 0.1 for UVB/vitamin D, higher if later modeling UVA damage

The MVP should not pretend sunscreen SPF maps cleanly to real-world protection. A conservative effective factor is better than a mathematically literal SPF factor.

### Burn-Risk Budget

The user chooses a skin sensitivity level. MVP can use a small number of presets rather than asking for Fitzpatrick type explicitly:

- very sensitive
- sensitive
- average
- less sensitive
- rarely burns

Each preset maps to a daily burn-risk threshold in UV-index-hours. This mapping should be calibration data in the shared core, not hardcoded in UI.

The app computes:

```text
burnBudgetUsed = accumulatedDamageDose / burnThreshold
remainingBudget = burnThreshold - accumulatedDamageDose
```

Time to burn is calculated by integrating the future UV forecast from now until `remainingBudget` is exhausted:

```text
for each future interval:
    dose += forecastUV * assumedExposureFactor * dt
    if dose >= remainingBudget:
        interpolate threshold crossing time
```

This is the core differentiator.

### Vitamin D Opportunity

Vitamin D should be modeled as an opportunity score, not a precise IU estimate.

For MVP:

```text
vitaminDOpportunity += UVBProxy(t) * exposedSkinFactor * outdoorProbability * duration
```

`UVBProxy` can initially be derived from UV index, solar elevation, season, and latitude. The model should include saturation: after some daily opportunity level, additional sunlight yields diminishing vitamin D value while damage dose continues accumulating.

The user-facing output should be categorical and state a numerical range:

- very low (x1 - x2)
- low (x2 - x3)
- moderate (x3 - x4)
- likely sufficient (x4 - x5)

This avoids false precision while still making the feature useful.

## Data Sources

The app needs current and forecast UV data for the user’s location.

MVP options:

- OpenUV or equivalent UV API.
- Weather provider with hourly UV forecast.
- Fallback approximate clear-sky model based on solar elevation if API is unavailable.

The UV provider abstraction should expose:

```kotlin
interface UvForecastProvider {
    suspend fun getForecast(location: GeoPoint, timeRange: TimeRange): UvForecast
}
```

`UvForecast` should include hourly or sub-hourly samples, current UV if available, provider metadata, and fetch timestamp.

## Android Signal Inputs

The Android MVP should use only low-friction signals:

- Fused Location Provider for current/coarse location.
- Significant location changes or periodic work for background updates.
- Activity Recognition for walking/running/still/in vehicle.
- Ambient light sensor when app is foregrounded or opportunistically available.
- Charging / device stationary state as weak context.
- Optional notification action buttons for corrections.

Avoid aggressive continuous sensing. Battery trust matters more than marginal inference quality.

### Outdoor Probability

Initial heuristic model:

```text
outdoorProbability = weighted combination of:
- current location confidence
- movement state
- ambient light if available
- recent manual correction
- time since last correction
- whether user is in vehicle
- UV/solar elevation plausibility
```

Simple rules are acceptable for MVP:

- Manual “indoors” overrides to 0.0 until disabled or timeout.
- Manual “outside” overrides to 1.0 until disabled or timeout.
- Walking/running plus location movement during daylight suggests outdoors.
- Still at same location for long periods suggests indoors unless manually corrected.
- In vehicle reduces vitamin D factor strongly due to window shielding.
- Nighttime UV is zero regardless of outdoor probability.

The model should always produce both `outdoorProbability` and `confidence`.

## Architecture

Use Kotlin Multiplatform for shared business logic, even though MVP ships Android only.

Recommended modules:

```text
shared-core
  domain models
  dose integrator
  solar geometry
  UV forecast abstractions
  vitamin D model
  burn-risk model
  exposure timeline reducer
  correction event logic
  tests

shared-persistence
  schema definitions and repositories, ideally SQLDelight-compatible

android-app
  Jetpack Compose UI
  Android location adapter
  Android activity recognition adapter
  Android background work
  Android notifications
  permission flow

android-data
  UV API client implementation
  local database implementation
```

Keep platform details out of the dose engine. The shared core should consume normalized evidence events rather than Android APIs directly.

```kotlin
data class ExposureEvidence(
    val timestamp: Instant,
    val location: GeoPoint?,
    val uvIndex: Double?,
    val activity: ActivityState?,
    val ambientLux: Double?,
    val manualState: ManualExposureState?,
    val confidence: Confidence
)
```

The core should reduce evidence and corrections into exposure intervals:

```kotlin
data class ExposureInterval(
    val start: Instant,
    val end: Instant,
    val location: GeoPoint?,
    val uvIndexMean: Double,
    val outdoorProbability: Double,
    val exposureFactor: Double,
    val damageDose: Double,
    val vitaminDOpportunity: Double,
    val confidence: Confidence
)
```

## Persistence

Store:

- User profile.
- Skin sensitivity preset.
- Manual correction events.
- UV forecast cache.
- Exposure evidence events.
- Reduced exposure intervals.
- Daily summary aggregates.

Local-first is sufficient. Retain raw evidence for a limited period, such as 7–30 days, and retain daily summaries longer.

## Forecasting Flow

At app startup or periodic refresh:

1. Get current coarse location.
2. Fetch UV forecast for today and near future.
3. Build or update the UV curve.
4. Combine with recent exposure evidence and manual corrections.
5. Recompute daily accumulated dose.
6. Recompute vitamin D opportunity.
7. Compute future threshold crossing time.
8. Update dashboard and optional notification.

Forecast integration should interpolate between hourly samples.

## Notifications

MVP notifications should be sparse and user-controlled.

Useful notifications:

- “You are nearing your configured UV budget.”
- “You likely reached today’s vitamin D opportunity.”
- “Still outside? Tap to mark shade / indoors / sunscreen.”

Avoid noisy UV warnings. The app should feel like a passive tracker, not an alarm system.

## Privacy

Location and exposure history are sensitive. MVP should be local-first and clear about what is stored.

Principles:

- No account required.
- No cloud sync in MVP.
- Store only coarse location where possible.
- Allow deleting all data.
- Explain background location use in plain language.
- Do not sell or share exposure/location data.

## Accuracy and Confidence

The app should show confidence rather than hiding uncertainty.

Examples:

- High confidence: active outdoor walk, good location, daytime, UV forecast available.
- Medium confidence: location known, no recent sensor evidence.
- Low confidence: stale location, conflicting manual state, or missing UV forecast.

The UI should avoid exact medical claims. Prefer:

- “estimated”
- “likely”
- “low confidence”
- “based on forecast and phone signals”

## MVP Screens

### Dashboard

Shows:

- UV dose budget used today.
- Vitamin D opportunity status.
- Current UV.
- Estimated time to burn if current exposure continues.
- Confidence.
- Quick correction buttons.

### Timeline

Shows today’s exposure intervals and manual corrections.

### Forecast

Shows the UV curve for the rest of the day and where the burn threshold would be crossed under current assumptions.

### Profile / Settings

Shows:

- Skin sensitivity preset.
- Typical clothing / exposed skin assumption.
- Sunscreen behavior.
- Notification preferences.
- Location/background permission status.
- Data deletion.

## MVP Implementation Plan

### Phase 1: Core Model Prototype

- Implement UV curve representation.
- Implement discrete dose integration.
- Implement burn threshold crossing from future forecast.
- Implement vitamin D opportunity score with saturation.
- Build unit tests with synthetic UV curves.

### Phase 2: Android Dashboard Prototype

- Basic Compose UI.
- Manual location or current location.
- Fetch UV forecast.
- Show current UV, daily UV curve, dose estimate, and time-to-threshold.
- Manual exposure buttons only; no passive inference yet.

### Phase 3: Passive Estimation

- Add background location updates.
- Add activity recognition.
- Add simple outdoor probability heuristic.
- Generate exposure intervals automatically.
- Add timeline inspection.

### Phase 4: Polish and Trust

- Add confidence labels.
- Add notification actions.
- Improve permission onboarding.
- Add data deletion and local privacy messaging.
- Tune thresholds and defaults.

## Open Questions

- Which UV provider is best for hourly forecast quality, cost, and availability?
- Should MVP use UV-index-hours or convert internally to erythemal dose units?
- What are defensible default burn thresholds for casual presets?
- How aggressive should background location be without harming battery trust?
- Should vitamin D be purely categorical in v1, or include a relative daily target meter?
- How long should manual corrections persist by default?

## Design Biases

Prefer passive over manual, but make manual correction trivial.

Prefer approximate and inspectable over complex and opaque.

Prefer categorical biological claims over false numerical precision.

Prefer local-first privacy over growth analytics.

Prefer Android-native sensing now, shared KMP core later.

Prefer “relationship with daylight” over “UV danger warning.”

